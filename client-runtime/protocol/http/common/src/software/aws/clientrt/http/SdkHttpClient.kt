/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package software.aws.clientrt.http

import software.aws.clientrt.client.ExecutionContext
import software.aws.clientrt.http.engine.HttpClientEngine
import software.aws.clientrt.http.request.HttpRequestBuilder
import software.aws.clientrt.http.request.HttpRequestPipeline
import software.aws.clientrt.http.request.PreparedHttpRequest
import software.aws.clientrt.http.response.HttpResponsePipeline

/**
 * Create an [SdkHttpClient] with the given engine, and optionally configure it
 */
@HttpClientDsl
fun sdkHttpClient(
    engine: HttpClientEngine,
    configure: HttpClientConfig.() -> Unit = {}
): SdkHttpClient {
    val config = HttpClientConfig().apply(configure)
    return SdkHttpClient(engine, config)
}

/**
 * An HTTP client capable of round tripping requests and responses
 *
 * **NOTE**: This is not a general purpose HTTP client. It is meant for generated SDK use.
 */
class SdkHttpClient(
    val engine: HttpClientEngine,
    val config: HttpClientConfig
) {

    /**
     * Request pipeline (middleware stack). Responsible for transforming inputs into an outgoing [software.aws.clientrt.http.request.HttpRequest]
     */
    val requestPipeline = HttpRequestPipeline()

    /**
     * Response pipeline. Responsible for transforming [software.aws.clientrt.http.response.HttpResponse] to the expected type
     */
    val responsePipeline = HttpResponsePipeline()

    init {
        // wire up the features
        config.install(this)

        // install ourselves into the engine
        engine.install(this)
    }

    /**
     * Shutdown this HTTP client and close any resources. The client will no longer be capable of making requests.
     */
    fun close() {
        engine.close()
    }
}

/**
 * Make an HTTP request with the given input type. The input type is expected to be transformable by the request
 * pipeline. The output type [TResponse] is expected to be producible by the response pipeline.
 */
suspend inline fun <reified TResponse> SdkHttpClient.roundTrip(context: ExecutionContext, builder: HttpRequestBuilder? = null): TResponse =
    PreparedHttpRequest(this, builder, context).receive()

/**
 * Make an HTTP request with the given [HttpRequestBuilder] and run the [block] with the result of the response pipeline.
 *
 * The underlying HTTP response will remain available until the block returns making this method suitable for
 * streaming responses.
 */
suspend inline fun <reified TResponse, R> SdkHttpClient.execute(
    context: ExecutionContext,
    crossinline block: suspend (TResponse) -> R
): R = PreparedHttpRequest(this, null, context).execute(block)

/**
 * Make an HTTP request with the given [HttpRequestBuilder] and run the [block] with the result of the response pipeline.
 *
 * The underlying HTTP response will remain available until the block returns making this method suitable for
 * streaming responses.
 */
suspend inline fun <reified TResponse, R> SdkHttpClient.execute(
    context: ExecutionContext,
    builder: HttpRequestBuilder,
    crossinline block: suspend (TResponse) -> R
): R = PreparedHttpRequest(this, builder, context).execute(block)
