/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.smithy.kotlin.runtime.http.operation

import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.client.LogMode
import aws.smithy.kotlin.runtime.client.endpoints.authOptions
import aws.smithy.kotlin.runtime.client.logMode
import aws.smithy.kotlin.runtime.collections.attributesOf
import aws.smithy.kotlin.runtime.collections.emptyAttributes
import aws.smithy.kotlin.runtime.collections.merge
import aws.smithy.kotlin.runtime.http.HttpCall
import aws.smithy.kotlin.runtime.http.HttpHandler
import aws.smithy.kotlin.runtime.http.auth.SignHttpRequest
import aws.smithy.kotlin.runtime.http.complete
import aws.smithy.kotlin.runtime.http.interceptors.InterceptorExecutor
import aws.smithy.kotlin.runtime.http.middleware.RetryMiddleware
import aws.smithy.kotlin.runtime.http.readAll
import aws.smithy.kotlin.runtime.http.request.HttpRequestBuilder
import aws.smithy.kotlin.runtime.http.request.dumpRequest
import aws.smithy.kotlin.runtime.http.request.immutableView
import aws.smithy.kotlin.runtime.http.request.toBuilder
import aws.smithy.kotlin.runtime.http.response.dumpResponse
import aws.smithy.kotlin.runtime.io.Handler
import aws.smithy.kotlin.runtime.io.middleware.Middleware
import aws.smithy.kotlin.runtime.io.middleware.Phase
import aws.smithy.kotlin.runtime.retries.RetryStrategy
import aws.smithy.kotlin.runtime.retries.StandardRetryStrategy
import aws.smithy.kotlin.runtime.retries.policy.RetryPolicy
import aws.smithy.kotlin.runtime.retries.policy.StandardRetryPolicy
import aws.smithy.kotlin.runtime.telemetry.logging.debug
import aws.smithy.kotlin.runtime.telemetry.logging.logger
import aws.smithy.kotlin.runtime.telemetry.logging.trace
import aws.smithy.kotlin.runtime.telemetry.metrics.measureSeconds
import kotlin.coroutines.coroutineContext
import aws.smithy.kotlin.runtime.io.middleware.decorate as decorateHandler

@InternalApi
public typealias SdkHttpRequest = OperationRequest<HttpRequestBuilder>

/**
 * Configure the execution of an operation from [Request] to [Response].
 *
 * An operation has several "phases" of its lifecycle that can be intercepted and customized.
 * Technically any phase can act on the request or the response. The phases are named with their intended use; however,
 * nothing prevents e.g. registering something in [initialize] that acts on the output type though.
 *
 * ## Middleware Phases
 *
 * ```
 * Initialize ---> <Serialize> ---> Mutate
 *                                     |
 *                                     v
 *                                  <Retry>
 *                                     |
 *                                     v
 *                              +---------------------+
 *                              |    OnEachAttempt    |
 *                              |        |            |
 *                              |        v            |
 *                              |  <ResolveIdentity>  |
 *                              |        |            |
 *                              |        v            |
 *                              |  <ResolveEndpoint>  |
 *                              |        |            |
 *                              |        v            |
 *                              |    <Signing>        |
 *                              |        |            |
 *                              |        v            |
 *                              |   <Deserialize>     |
 *                              |        |            |
 *                              |        v            |
 *                              |     Receive         |
 *                              |        |            |
 *                              |        v            |
 *                              |    <HttpClient>     |
 *                              +---------------------+
 * ```
 *
 * In the above diagram the phase relationships and sequencing are shown. Requests start at [initialize] and proceed
 * until the actual HTTP client engine call. The response then is returned up the call stack and traverses the phases
 * in reverse.
 *
 * Phases enclosed in brackets `<>` are implicit phases that are always present and cannot be intercepted directly
 * (only one is allowed to exist). These are usually configured directly when the operation is built.
 *
 * ### Default Behaviors
 *
 * By default, every operation is:
 * * Retried using the configured [retryStrategy] and [retryPolicy].
 * * Signed using the resolved authentication scheme
 */
@InternalApi
public class SdkOperationExecution<Request, Response> {

    /**
     * **Request Middlewares**: Prepare the input [Request] (e.g. set any default parameters if needed) before
     * serialization
     *
     * **Response Middlewares**: Finalize the [Response] before returning it to the caller
     */
    public val initialize: Phase<OperationRequest<Request>, Response> = Phase<OperationRequest<Request>, Response>()

    /**
     * **Request Middlewares**: Modify the outgoing HTTP request. This phase runs BEFORE the retry loop and
     * is suitable for any middleware that only needs to run once and does not need to modify the outgoing request
     * per/attempt.
     *
     * At this phase the [Request] (operation input) has been serialized to an HTTP request.
     *
     * **Response Middlewares**: Modify the output after deserialization
     */
    public val mutate: Phase<SdkHttpRequest, Response> = Phase<SdkHttpRequest, Response>()

    /**
     * **Request Middlewares**: Modify the outgoing HTTP request. This phase is conceptually the same as [mutate]
     * but it runs on every attempt. Each attempt will not see modifications made by previous attempts.
     *
     * **Response Middlewares**: Modify the output after deserialization on a per/attempt basis.
     */
    public val onEachAttempt: Phase<SdkHttpRequest, Response> = Phase<SdkHttpRequest, Response>()

    /**
     * **Request Middlewares**: First chance to intercept after signing (and last chance before the final request is sent).
     *
     * **Response Middlewares**: First chance to intercept after the raw HTTP response is received and before deserialization
     * into operation output type [Response].
     */
    public val receive: Phase<SdkHttpRequest, HttpCall> = Phase<SdkHttpRequest, HttpCall>()

    /**
     * The authentication config to use. Defaults to [OperationAuthConfig.Anonymous] which uses
     * anonymous authentication (no auth).
     */
    public var auth: OperationAuthConfig = OperationAuthConfig.Anonymous

    /**
     * The endpoint resolver for the operation
     */
    public var endpointResolver: EndpointResolver? = null

    /**
     * The retry strategy to use. Defaults to [StandardRetryStrategy]
     */
    public var retryStrategy: RetryStrategy = StandardRetryStrategy()

    /**
     * The retry policy to use. Defaults to [StandardRetryPolicy.Default]
     */
    public var retryPolicy: RetryPolicy<Response> = StandardRetryPolicy.Default
}

/**
 * Decorate the "raw" [HttpHandler] with the execution phases (middleware) of this operation and
 * return a handler to be used specifically for the given operation.
 */
internal fun <Request, Response> SdkOperationExecution<Request, Response>.decorate(
    handler: HttpHandler,
    op: SdkHttpOperation<Request, Response>,
): Handler<OperationRequest<Request>, Response> {
    val interceptors = InterceptorExecutor<Request, Response>(op.context, op.interceptors, op.typeInfo)
    // ensure http calls are tracked
    receive.register(HttpCallMiddleware())
    // run before trace middleware because interceptors can modify right before sending to an engine
    receive.register(InterceptorTransmitMiddleware(interceptors))
    receive.intercept(Phase.Order.After, ::httpTraceMiddleware)

    val receiveHandler = decorateHandler(handler, receive)
    val deserializeHandler = op.deserializer.decorate(receiveHandler, interceptors)

    val authHandler = AuthHandler(deserializeHandler, interceptors, auth, endpointResolver)
    val onEachAttemptHandler = decorateHandler(authHandler, onEachAttempt)
    val retryHandler = decorateHandler(onEachAttemptHandler, RetryMiddleware(retryStrategy, retryPolicy, interceptors))

    val mutateHandler = decorateHandler(MutateHandler(retryHandler), mutate)
    val serializeHandler = op.serializer.decorate(mutateHandler, interceptors)
    val initializeHandler = decorateHandler(InitializeHandler(serializeHandler), initialize)
    return OperationHandler(initializeHandler, interceptors)
}

private fun <I, O> HttpSerializer<I>.decorate(
    inner: Handler<SdkHttpRequest, O>,
    interceptors: InterceptorExecutor<I, O>,
): Handler<OperationRequest<I>, O> = SerializeHandler(inner, this, interceptors)

private fun <I, O> HttpDeserializer<O>.decorate(
    inner: Handler<SdkHttpRequest, HttpCall>,
    interceptors: InterceptorExecutor<I, O>,
): Handler<SdkHttpRequest, O> = DeserializeHandler(inner, this, interceptors)

// internal glue used to marry one phase to another

/**
 * Outermost handler that wraps the entire middleware pipeline and handles interceptor hooks related
 * to the start/end of an operation
 */
private class OperationHandler<Input, Output>(
    private val inner: Handler<OperationRequest<Input>, Output>,
    private val interceptors: InterceptorExecutor<Input, Output>,
) : Handler<OperationRequest<Input>, Output> {
    override suspend fun call(request: OperationRequest<Input>): Output {
        coroutineContext.trace<OperationHandler<*, *>> { "operation started" }
        val result = interceptors.readBeforeExecution(request.subject)
            .mapCatching {
                inner.call(request)
            }
            .let {
                interceptors.modifyBeforeCompletion(it)
            }
            .let {
                interceptors.readAfterExecution(it)
            }

        when {
            result.isSuccess -> coroutineContext.trace<OperationHandler<*, *>> { "operation completed successfully" }
            result.isFailure -> coroutineContext.trace<OperationHandler<*, *>>(result.exceptionOrNull()) { "operation failed" }
        }
        return result.getOrThrow()
    }
}

private class InitializeHandler<Input, Output>(
    private val inner: Handler<Input, Output>,
) : Handler<Input, Output> {
    override suspend fun call(request: Input): Output = inner.call(request)
}

private class SerializeHandler<Input, Output> (
    private val inner: Handler<SdkHttpRequest, Output>,
    private val serializer: HttpSerializer<Input>,
    private val interceptors: InterceptorExecutor<Input, Output>,
) : Handler<OperationRequest<Input>, Output> {

    override suspend fun call(request: OperationRequest<Input>): Output {
        val modified = interceptors.modifyBeforeSerialization(request.subject)
            .let { request.copy(subject = it) }

        interceptors.readBeforeSerialization(modified.subject)

        // store finalized operation input for later middleware to read if needed
        request.context[HttpOperationContext.OperationInput] = modified.subject as Any

        val requestBuilder = when (serializer) {
            is HttpSerializer.NonStreaming -> serializer.serialize(modified.context, modified.subject)
            is HttpSerializer.Streaming -> serializer.serialize(modified.context, modified.subject)
        }
        interceptors.readAfterSerialization(requestBuilder.immutableView())

        return inner.call(SdkHttpRequest(modified.context, requestBuilder))
    }
}

private class MutateHandler<Output> (
    private val inner: Handler<SdkHttpRequest, Output>,
) : Handler<SdkHttpRequest, Output> {
    override suspend fun call(request: SdkHttpRequest): Output = inner.call(request)
}

internal class AuthHandler<Input, Output>(
    private val inner: Handler<SdkHttpRequest, Output>,
    private val interceptors: InterceptorExecutor<Input, Output>,
    private val authConfig: OperationAuthConfig,
    private val endpointResolver: EndpointResolver? = null,
) : Handler<SdkHttpRequest, Output> {

    override suspend fun call(request: SdkHttpRequest): Output {
        // select an auth scheme by reconciling the (priority) list of candidates returned from the resolver
        // with the ones actually configured/available for the SDK
        val candidateAuthSchemes = authConfig.authSchemeResolver.resolve(request)
        val authOption = candidateAuthSchemes.firstOrNull { it.schemeId in authConfig.configuredAuthSchemes } ?: error("no auth scheme found for operation; candidates: $candidateAuthSchemes")
        val authScheme = authConfig.configuredAuthSchemes[authOption.schemeId] ?: error("auth scheme ${authOption.schemeId} not configured")

        val schemeAttr = attributesOf {
            "auth.scheme_id" to authScheme.schemeId.id
        }

        // properties need to propagate from AuthOption to signer and identity provider
        request.context.merge(authOption.attributes)

        // resolve identity from the selected auth scheme
        val identityProvider = authScheme.identityProvider(authConfig.identityProviderConfig)
        val identity = request.context.operationMetrics.resolveIdentityDuration.measureSeconds(schemeAttr) {
            identityProvider.resolve(request.context)
        }

        val resolveEndpointReq = ResolveEndpointRequest(request.context, request.subject.immutableView(), identity)

        if (endpointResolver != null) {
            val endpoint = request.context.operationMetrics.resolveEndpointDuration.measureSeconds(request.context.operationAttributes) {
                endpointResolver.resolve(resolveEndpointReq)
            }
            coroutineContext.debug<AuthHandler<*, *>> { "resolved endpoint: $endpoint" }
            setResolvedEndpoint(request, endpoint)
            // update the request context with endpoint specific auth signing context
            val endpointAuthAttributes = endpoint.authOptions.firstOrNull { it.schemeId == authScheme.schemeId }?.attributes ?: emptyAttributes()
            request.context.merge(endpointAuthAttributes)

            // also update the request context with endpoint attributes
            request.context.merge(endpoint.attributes)
        }

        val modified = interceptors.modifyBeforeSigning(request.subject.immutableView(true))
            .let { request.copy(subject = it.toBuilder()) }

        interceptors.readBeforeSigning(modified.subject.immutableView())

        val signingRequest = SignHttpRequest(modified.subject, identity, modified.context)

        request.context.operationMetrics.signingDuration.measureSeconds(schemeAttr) {
            authScheme.signer.sign(signingRequest)
        }

        interceptors.readAfterSigning(modified.subject.immutableView())
        return inner.call(modified)
    }
}

private class DeserializeHandler<Input, Output>(
    private val inner: Handler<SdkHttpRequest, HttpCall>,
    private val deserializer: HttpDeserializer<Output>,
    private val interceptors: InterceptorExecutor<Input, Output>,
) : Handler<SdkHttpRequest, Output> {

    override suspend fun call(request: SdkHttpRequest): Output {
        val call = inner.call(request)

        val modified = interceptors.modifyBeforeDeserialization(call)
            .let { call.copy(response = it) }

        interceptors.readBeforeDeserialization(modified)

        val output = when (deserializer) {
            is HttpDeserializer.NonStreaming -> {
                val payload = modified.response.body.readAll()
                deserializer.deserialize(request.context, modified, payload)
            }
            is HttpDeserializer.Streaming -> deserializer.deserialize(request.context, modified)
        }
        interceptors.readAfterDeserialization(output, modified)

        return output
    }
}

/**
 * default middleware that handles managing the HTTP call list
 */
private class HttpCallMiddleware : Middleware<SdkHttpRequest, HttpCall> {
    private val callList: MutableList<HttpCall> = mutableListOf()

    override suspend fun <H : Handler<SdkHttpRequest, HttpCall>> handle(request: SdkHttpRequest, next: H): HttpCall {
        if (callList.isNotEmpty()) {
            // an existing call was made and we are retrying for some reason, ensure the resources from the previous
            // attempt are released
            callList.last().complete()
        }
        val call = next.call(request)
        callList.add(call)

        request.context[HttpOperationContext.HttpCallList] = callList
        return call
    }
}

/**
 * default middleware that logs requests/responses
 */
private suspend fun httpTraceMiddleware(request: SdkHttpRequest, next: Handler<SdkHttpRequest, HttpCall>): HttpCall {
    val logMode = request.context.logMode
    val logger = coroutineContext.logger("httpTraceMiddleware")

    if (logMode.isEnabled(LogMode.LogRequest) || logMode.isEnabled(LogMode.LogRequestWithBody)) {
        val formattedReq = dumpRequest(request.subject, logMode.isEnabled(LogMode.LogRequestWithBody))
        logger.debug { "HttpRequest:\n$formattedReq" }
    }

    var call = next.call(request)

    if (logMode.isEnabled(LogMode.LogResponse) || logMode.isEnabled(LogMode.LogResponseWithBody)) {
        val (resp, formattedResp) = dumpResponse(call.response, logMode.isEnabled(LogMode.LogResponseWithBody))
        call = call.copy(response = resp)
        logger.debug { "HttpResponse:\n$formattedResp" }
    } else {
        logger.debug { "HttpResponse: ${call.response.status}" }
    }

    return call
}

/**
 * Default middleware that handles interceptor hooks for before/after transmit
 */
private class InterceptorTransmitMiddleware<I, O>(
    private val interceptors: InterceptorExecutor<I, O>,
) : Middleware<SdkHttpRequest, HttpCall> {
    override suspend fun <H : Handler<SdkHttpRequest, HttpCall>> handle(request: SdkHttpRequest, next: H): HttpCall {
        val modified = interceptors.modifyBeforeTransmit(request.subject.immutableView(true))
            .let { request.copy(subject = it.toBuilder()) }
        interceptors.readBeforeTransmit(modified.subject.immutableView())
        val call = next.call(modified)
        interceptors.readAfterTransmit(call)
        return call
    }
}
