/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.smithy.kotlin.runtime.http.operation

import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.client.SdkClientOption
import aws.smithy.kotlin.runtime.collections.get
import aws.smithy.kotlin.runtime.http.HttpHandler
import aws.smithy.kotlin.runtime.http.complete
import aws.smithy.kotlin.runtime.http.interceptors.HttpInterceptor
import aws.smithy.kotlin.runtime.operation.ExecutionContext
import aws.smithy.kotlin.runtime.telemetry.trace.withSpan
import aws.smithy.kotlin.runtime.util.Uuid
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlin.reflect.KClass

/**
 * A (Smithy) HTTP based operation.
 * @property execution Phases used to execute the operation request and get a response instance
 * @property context An [ExecutionContext] instance scoped to this operation
 * @property serializer The component responsible for serializing the input type `I` into an HTTP request builder
 * @property deserializer The component responsible for deserializing an HTTP response into the output type `O`
 * @property typeInfo the operation type info used internally for interceptors to function correctly
 * @property telemetry the telemetry parameters used to instrument the operation with
 */
@InternalApi
public class SdkHttpOperation<I, O> internal constructor(
    public val execution: SdkOperationExecution<I, O>,
    public val context: ExecutionContext,
    internal val serializer: HttpSerializer<I>,
    internal val deserializer: HttpDeserializer<O>,
    internal val typeInfo: OperationTypeInfo,
    internal val telemetry: SdkOperationTelemetry,
) {

    @Suppress("DEPRECATION")
    internal constructor(
        execution: SdkOperationExecution<I, O>,
        context: ExecutionContext,
        serializer: HttpSerialize<I>,
        deserializer: HttpDeserialize<O>,
        typeInfo: OperationTypeInfo,
        telemetry: SdkOperationTelemetry,
    ) : this(execution, context, serializer.intoSerializer(), deserializer.intoDeserializer(), typeInfo, telemetry)

    init {
        context[HttpOperationContext.SdkInvocationId] = Uuid.random().toString()
    }

    /**
     * Interceptors that will be executed as part of this operation. The difference between phases and interceptors
     * is the former is internal only whereas the latter is external customer facing. Middleware is also allowed to
     * suspend whereas interceptors are meant to be executed quickly.
     */
    public val interceptors: MutableList<HttpInterceptor> = mutableListOf()

    /**
     * Install a middleware into this operation's execution stack
     */
    public fun install(middleware: ModifyRequestMiddleware) { middleware.install(this) }

    // Convenience overloads for various types of middleware that target different phases
    // NOTE: Using install isn't strictly necessary, it's just a pattern for self registration
    public fun install(middleware: InitializeMiddleware<I, O>) { middleware.install(this) }
    public fun install(middleware: MutateMiddleware<O>) { middleware.install(this) }
    public fun install(middleware: ReceiveMiddleware) { middleware.install(this) }
    public fun install(middleware: InlineMiddleware<I, O>) { middleware.install(this) }

    @InternalApi
    public companion object {
        public inline fun <reified I, reified O> build(block: SdkHttpOperationBuilder<I, O>.() -> Unit): SdkHttpOperation<I, O> =
            SdkHttpOperationBuilder<I, O>(
                I::class,
                O::class,
            ).apply(block).build()
    }
}

/**
 * Gets the unique ID that identifies the active SDK request in this [ExecutionContext].
 */
@InternalApi
public val ExecutionContext.sdkInvocationId: String
    get() = get(HttpOperationContext.SdkInvocationId)

/**
 * Round trip an operation using the given [HttpHandler]
 */
@InternalApi
public suspend fun <I, O> SdkHttpOperation<I, O>.roundTrip(
    httpHandler: HttpHandler,
    input: I,
): O = execute(httpHandler, input) { it }

/**
 * Make an operation request with the given [input] and return the result of executing [block] with the output.
 *
 * The response and any resources will remain open until the end of the [block]. This facilitates streaming
 * output responses where the underlying raw HTTP connection needs to remain open
 */
@InternalApi
public suspend fun <I, O, R> SdkHttpOperation<I, O>.execute(
    httpHandler: HttpHandler,
    input: I,
    block: suspend (O) -> R,
): R {
    val handler = execution.decorate(httpHandler, this)
    val request = OperationRequest(context, input)
    val (span, telemetryCtx) = instrument()
    try {
        return withSpan(span, telemetryCtx) {
            val output = handler.call(request)
            block(output)
        }
    } finally {
        context.cleanup()
    }
}

internal data class OperationTypeInfo(
    val inputType: KClass<*>,
    val outputType: KClass<*>,
)

@InternalApi
public class SdkHttpOperationBuilder<I, O> (
    private val inputType: KClass<*>,
    private val outputType: KClass<*>,
) {
    public val telemetry: SdkOperationTelemetry = SdkOperationTelemetry()

    @Suppress("DEPRECATION")
    @Deprecated("use serializeWith")
    public var serializer: HttpSerialize<I>? = null
        set(value) {
            field = value
            serializeWith = value?.intoSerializer()
        }

    public var serializeWith: HttpSerializer<I>? = null

    @Suppress("DEPRECATION")
    @Deprecated("use deserializeWith")
    public var deserializer: HttpDeserialize<O>? = null
        set(value) {
            field = value
            deserializeWith = value?.intoDeserializer()
        }

    public var deserializeWith: HttpDeserializer<O>? = null

    public val execution: SdkOperationExecution<I, O> = SdkOperationExecution()
    public val context: ExecutionContext = ExecutionContext()

    /**
     * The name of the operation
     */
    public var operationName: String? = null

    /**
     * The name of the service the request is sent to
     */
    public var serviceName: String? = null

    /**
     * (Optional) prefix to prepend to a (resolved) hostname
     */
    public var hostPrefix: String? = null

    public fun build(): SdkHttpOperation<I, O> {
        val opSerializer = requireNotNull(serializeWith) { "SdkHttpOperation.serializeWith must not be null" }
        val opDeserializer = requireNotNull(deserializeWith) { "SdkHttpOperation.deserializeWith must not be null" }
        requireNotNull(operationName) { "operationName is a required HTTP execution attribute" }
        requireNotNull(serviceName) { "serviceName is a required HTTP execution attribute" }
        context[SdkClientOption.OperationName] = operationName!!
        context[SdkClientOption.ServiceName] = serviceName!!
        hostPrefix?.let { context[HttpOperationContext.HostPrefix] = it }
        val typeInfo = OperationTypeInfo(inputType, outputType)
        return SdkHttpOperation(execution, context, opSerializer, opDeserializer, typeInfo, telemetry)
    }
}

/**
 * Configure HTTP operation context elements
 */
@InternalApi
public inline fun <I, O> SdkHttpOperationBuilder<I, O>.context(block: ExecutionContext.() -> Unit) {
    context.apply(block)
}

private suspend fun ExecutionContext.cleanup() {
    // pull the raw response(s) out of the context and cleanup any resources
    getOrNull(HttpOperationContext.HttpCallList)?.forEach { it.complete() }

    // at this point everything associated with this single operation should be cleaned up
    coroutineContext.job.cancelAndJoin()
}
