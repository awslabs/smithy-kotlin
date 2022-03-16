/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package aws.smithy.kotlin.runtime.http.engine.ktor

import aws.smithy.kotlin.runtime.http.Headers
import aws.smithy.kotlin.runtime.http.HttpStatusCode
import aws.smithy.kotlin.runtime.http.engine.*
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.http.response.HttpCall
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import aws.smithy.kotlin.runtime.logging.Logger
import aws.smithy.kotlin.runtime.logging.trace
import aws.smithy.kotlin.runtime.time.Instant
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.CoroutineContext
import io.ktor.client.engine.HttpClientEngine as KtorHttpClientEngine

/**
 * Utility class that wraps the given Ktor engine as an [HttpClientEngine].
 * This class can be used to wrap any Ktor compliant engine (though not all engines
 * may support HTTP features required by any given SDK).
 */
class KtorEngine(
    private val engine: KtorHttpClientEngine
) : HttpClientEngineBase("ktor") {

    @Deprecated(
        message = "KtorEngine was previously synonymous with the OkHttp engine. It has been modified to wrap any " +
            "Ktor compliant engine. The default engine has been changed from CRT to Ktor/OkHttp. To fix either " +
            "remove setting `httpClientEngine` explicitly or instantiate a Ktor compliant engine of your own and " +
            "use KtorEngine to wrap it. This constructor will be removed in a future release before GA.",
        level = DeprecationLevel.ERROR
    )
    constructor(config: HttpClientEngineConfig = HttpClientEngineConfig.Default) : this(DeprecationEngine)

    val client: HttpClient = HttpClient(engine) {
        // do not throw exceptions if status code < 300, error handling is expected by generated clients
        expectSuccess = false

        // do not attempt to follow redirects for status codes like 301 because they should be handled higher up
        followRedirects = false
    }

    private val logger = Logger.getLogger<KtorEngine>()

    override suspend fun roundTrip(request: HttpRequest): HttpCall {
        val callContext = callContext()

        val respChannel = Channel<HttpCall>(Channel.RENDEZVOUS)

        // run the request in another coroutine to allow streaming body to be handled
        launch(callContext + ioDispatcher()) {
            try {
                execute(callContext, request, respChannel)
            } catch (ex: Exception) {
                // signal the HTTP response isn't coming
                respChannel.close(ex)
            }
        }

        // wait for the response to be available, the content will be read as a stream
        logger.trace("waiting on response to be available")

        try {
            val resp = respChannel.receive()
            logger.trace("response is available continuing")
            return resp
        } catch (ex: Exception) {
            throw logger.throwing(ex)
        }
    }

    private suspend fun execute(
        callContext: CoroutineContext,
        sdkRequest: HttpRequest,
        channel: SendChannel<HttpCall>
    ) {
        val builder = KtorRequestAdapter(sdkRequest, callContext).toBuilder()
        val waiter = Waiter()
        val reqTime = Instant.now()
        client.request<HttpStatement>(builder).execute { httpResp ->
            val respTime = Instant.now()
            // we have a lifetime problem here...the stream (and HttpResponse instance) are only valid
            // until the end of this block. We don't know if the consumer wants to read the content fully or
            // stream it. We need to wait until the entire content has been read before leaving the block and
            // releasing the underlying network resources. We do this by blocking until the request job
            // completes, at which point we signal it's safe to exit the block and release the underlying resources.
            callContext.job.invokeOnCompletion { waiter.signal() }

            val body = KtorHttpBody(httpResp.contentLength(), httpResp.content)

            // copy the headers so that we no longer depend on the underlying ktor HttpResponse object
            // outside of the body content (which will signal once read that it is safe to exit the block)
            val headers = Headers { appendAll(KtorHeaders(httpResp.headers)) }

            val resp = HttpResponse(
                HttpStatusCode.fromValue(httpResp.status.value),
                headers,
                body,
            )

            logger.trace("signalling response")
            val call = HttpCall(sdkRequest, resp, reqTime, respTime, callContext)
            channel.send(call)

            logger.trace("waiting on body to be consumed")
            // wait for the receiving end to finish with the HTTP body
            waiter.wait()
            logger.trace("request done")
        }
    }

    override fun close() {
        client.close()
        engine.close()
    }
}

/**
 * Simple notify mechanism that waits for a signal
 */
internal class Waiter {
    private val mutex = Mutex(locked = true)

    // wait for the signal
    suspend fun wait() { mutex.lock() }

    // give the signal to continue
    fun signal() { mutex.unlock() }
}

// FIXME - dummy engine for deprecated constructor, remove before GA
private object DeprecationEngine : KtorHttpClientEngine {
    override val config: io.ktor.client.engine.HttpClientEngineConfig
        get() = error("not a real engine")
    override val coroutineContext: CoroutineContext
        get() = error("not a real engine")
    override val dispatcher: CoroutineDispatcher
        get() = error("not a real engine")

    override fun close() {}

    @InternalAPI
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        error("not a real engine")
    }
}
