/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.smithy.kotlin.runtime.http.engine.okhttp

import aws.smithy.kotlin.runtime.http.HttpCall
import aws.smithy.kotlin.runtime.http.config.EngineFactory
import aws.smithy.kotlin.runtime.http.engine.*
import aws.smithy.kotlin.runtime.http.engine.internal.HttpClientMetrics
import aws.smithy.kotlin.runtime.http.engine.internal.ThreadState
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.net.TlsVersion
import aws.smithy.kotlin.runtime.operation.ExecutionContext
import aws.smithy.kotlin.runtime.time.Instant
import aws.smithy.kotlin.runtime.time.fromEpochMilliseconds
import kotlinx.coroutines.job
import okhttp3.*
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.time.toJavaDuration
import aws.smithy.kotlin.runtime.net.TlsVersion as SdkTlsVersion
import okhttp3.TlsVersion as OkHttpTlsVersion

/**
 * [aws.smithy.kotlin.runtime.http.engine.HttpClientEngine] based on OkHttp.
 */
public class OkHttpEngine(
    override val config: OkHttpEngineConfig,
) : HttpClientEngineBase("OkHttp") {
    public constructor() : this(OkHttpEngineConfig.Default)

    public companion object : EngineFactory<OkHttpEngineConfig.Builder, OkHttpEngine> {
        /**
         * Initializes a new [OkHttpEngine] via a DSL builder block
         * @param block A receiver lambda which sets the properties of the config to be built
         */
        public operator fun invoke(block: OkHttpEngineConfig.Builder.() -> Unit): OkHttpEngine =
            OkHttpEngine(OkHttpEngineConfig(block))

        override val engineConstructor: (OkHttpEngineConfig.Builder.() -> Unit) -> OkHttpEngine = ::invoke
    }

    private val dispatcher = config.buildDispatcher()
    private val metrics = HttpClientMetrics(
        scope = TELEMETRY_SCOPE,
        provider = config.telemetryProvider,
        threadStateCallback = dispatcher::getThreadState,
    )
    private val client = config.buildClient(dispatcher, metrics)

    override suspend fun roundTrip(context: ExecutionContext, request: HttpRequest): HttpCall {
        val callContext = callContext()

        val engineRequest = request.toOkHttpRequest(context, callContext, metrics)
        val engineCall = client.newCall(engineRequest)
        val engineResponse = mapOkHttpExceptions { engineCall.executeAsync() }

        callContext.job.invokeOnCompletion {
            engineResponse.body.close()
        }

        val response = engineResponse.toSdkResponse()
        val requestTime = Instant.fromEpochMilliseconds(engineResponse.sentRequestAtMillis)
        val responseTime = Instant.fromEpochMilliseconds(engineResponse.receivedResponseAtMillis)

        return OkHttpCall(request, response, requestTime, responseTime, callContext, engineCall)
    }

    override fun shutdown() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        metrics.close()
    }
}

private fun Dispatcher.getThreadState(): ThreadState? = (executorService as? ThreadPoolExecutor)?.let { executor ->
    val active = executor.activeCount.toLong()
    val idle = executor.poolSize.toLong() - active
    ThreadState(idle, active)
}

private fun OkHttpEngineConfig.buildDispatcher() = Dispatcher().apply {
    maxRequests = maxConcurrency.toInt()
    maxRequestsPerHost = maxConcurrencyPerHost.toInt()
}

/**
 * Convert SDK version of HTTP configuration to OkHttp specific configuration and return the configured client
 */
private fun OkHttpEngineConfig.buildClient(dispatcher: Dispatcher, metrics: HttpClientMetrics): OkHttpClient {
    val config = this

    return OkHttpClient.Builder().apply {
        // don't follow redirects
        followRedirects(false)
        followSslRedirects(false)

        connectionSpecs(listOf(minTlsConnectionSpec(config.tlsContext), ConnectionSpec.CLEARTEXT))

        // Transient connection errors are handled by retry strategy (exceptions are wrapped and marked retryable
        // appropriately internally). We don't want inner retry logic that inflates the number of retries.
        retryOnConnectionFailure(false)

        connectTimeout(config.connectTimeout.toJavaDuration())
        readTimeout(config.socketReadTimeout.toJavaDuration())
        writeTimeout(config.socketWriteTimeout.toJavaDuration())

        // FIXME - register a [ConnectionListener](https://github.com/square/okhttp/blob/master/okhttp/src/jvmMain/kotlin/okhttp3/ConnectionListener.kt#L27)
        // when a new okhttp release is cut that contains this abstraction and wireup connection uptime metrics

        // use our own pool configured with the timeout settings taken from config
        val pool = ConnectionPool(
            maxIdleConnections = 5, // The default from the no-arg ConnectionPool() constructor
            keepAliveDuration = config.connectionIdleTimeout.inWholeMilliseconds,
            TimeUnit.MILLISECONDS,
        )
        connectionPool(pool)

        dispatcher(dispatcher)

        // Log events coming from okhttp. Allocate a new listener per-call to facilitate dedicated trace spans.
        eventListenerFactory { call -> HttpEngineEventListener(pool, config.hostResolver, dispatcher, metrics, call) }

        // map protocols
        if (config.tlsContext.alpn.isNotEmpty()) {
            val protocols = config.tlsContext.alpn.mapNotNull {
                when (it) {
                    AlpnId.HTTP1_1 -> Protocol.HTTP_1_1
                    AlpnId.HTTP2 -> Protocol.HTTP_2
                    AlpnId.H2_PRIOR_KNOWLEDGE -> Protocol.H2_PRIOR_KNOWLEDGE
                    else -> null
                }
            }
            protocols(protocols)
        }

        proxySelector(OkHttpProxySelector(config.proxySelector))
        proxyAuthenticator(OkHttpProxyAuthenticator(config.proxySelector))

        dns(OkHttpDns(config.hostResolver))

        addInterceptor(MetricsInterceptor)
    }.build()
}

private fun minTlsConnectionSpec(tlsContext: TlsContext): ConnectionSpec {
    val minVersion = tlsContext.minVersion ?: TlsVersion.TLS_1_2
    val okHttpTlsVersions = SdkTlsVersion
        .values()
        .filter { it >= minVersion }
        .sortedDescending() // Prioritize higher TLS versions first
        .map(::toOkHttpTlsVersion)
        .toTypedArray()
    return ConnectionSpec
        .Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(*okHttpTlsVersions)
        .build()
}

private fun toOkHttpTlsVersion(sdkTlsVersion: SdkTlsVersion): OkHttpTlsVersion = when (sdkTlsVersion) {
    SdkTlsVersion.TLS_1_0 -> OkHttpTlsVersion.TLS_1_0
    SdkTlsVersion.TLS_1_1 -> OkHttpTlsVersion.TLS_1_1
    SdkTlsVersion.TLS_1_2 -> OkHttpTlsVersion.TLS_1_2
    SdkTlsVersion.TLS_1_3 -> OkHttpTlsVersion.TLS_1_3
}
