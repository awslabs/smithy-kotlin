/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.http.engine.internal

import aws.smithy.kotlin.runtime.ExperimentalApi
import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.collections.Attributes
import aws.smithy.kotlin.runtime.collections.attributesOf
import aws.smithy.kotlin.runtime.io.Closeable
import aws.smithy.kotlin.runtime.telemetry.TelemetryProvider
import aws.smithy.kotlin.runtime.telemetry.metrics.DoubleHistogram
import aws.smithy.kotlin.runtime.telemetry.metrics.LongAsyncMeasurement
import aws.smithy.kotlin.runtime.telemetry.metrics.MonotonicCounter
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

/**
 * Common attributes for [HttpClientMetrics]
 */
@InternalApi
public object HttpClientMetricAttributes {
    public val IdleConnection: Attributes = attributesOf { "state" to "idle" }
    public val AcquiredConnection: Attributes = attributesOf { "state" to "acquired" }
    public val QueuedRequest: Attributes = attributesOf { "state" to "queued" }
    public val InFlightRequest: Attributes = attributesOf { "state" to "in-flight" }
    public val IdleThread: Attributes = attributesOf { "state" to "idle" }
    public val ActiveThread: Attributes = attributesOf { "state" to "active" }
}

@InternalApi
public data class ThreadState(val idle: Long, val active: Long)

/**
 * Container for common HTTP engine related metrics. Engine implementations can re-use this and update
 * the various fields in whatever manner fits best (increment/decrement vs current absolute value).
 *
 * @param scope the instrumentation scope
 * @param provider the telemetry provider to instrument with
 */
@OptIn(ExperimentalApi::class)
@InternalApi
public class HttpClientMetrics(
    scope: String,
    public val provider: TelemetryProvider,
    threadStateCallback: (() -> ThreadState?)? = null,
) : Closeable {
    private val meter = provider.meterProvider.getOrCreateMeter(scope)

    private val _connectionsLimit = atomic(0L)
    private val _idleConnections = atomic(0L)
    private val _acquiredConnections = atomic(0L)
    private val _requestConcurrencyLimit = atomic(0L)
    private val _queuedRequests = atomic(0L)
    private val _inFlightRequests = atomic(0L)

    /**
     * The amount of time it takes to acquire a connection from the pool
     */
    public val connectionAcquireDuration: DoubleHistogram = meter.createDoubleHistogram(
        "smithy.client.http.connections.acquire_duration",
        "s",
        "The amount of time requests take to acquire a connection from the pool",
    )

    /**
     * The amount of time a request spent queued waiting to be executed by the HTTP client
     */
    public val requestsQueuedDuration: DoubleHistogram = meter.createDoubleHistogram(
        "smithy.client.http.requests.queued_duration",
        "s",
        "The amount of time a request spent queued waiting to be executed by the HTTP client",
    )

    /**
     * The amount of time a connection has been open
     */
    public val connectionUptime: DoubleHistogram = meter.createDoubleHistogram(
        "smithy.client.http.connections.uptime",
        "s",
        "The amount of time a connection has been open",
    )

    public val bytesSent: MonotonicCounter = meter.createMonotonicCounter(
        "smithy.client.http.bytes_sent",
        "By",
        "The total number of bytes sent by the HTTP client",
    )

    public val bytesReceived: MonotonicCounter = meter.createMonotonicCounter(
        "smithy.client.http.bytes_received",
        "By",
        "The total number of bytes received by the HTTP client",
    )

    public val timeToFirstByteDuration: DoubleHistogram = meter.createDoubleHistogram(
        "smithy.client.http.time_to_first_byte",
        "s",
        "The amount of time after a request has been sent spent waiting on a response from the remote server",
    )

    private val asyncHandles = listOfNotNull(
        meter.createAsyncUpDownCounter(
            "smithy.client.http.connections.limit",
            { it.record(_connectionsLimit.value) },
            "{connection}",
            "Max connections configured for the HTTP client",
        ),
        meter.createAsyncUpDownCounter(
            "smithy.client.http.connections.usage",
            ::recordConnectionState,
            "{connection}",
            "Current state of connections (idle, acquired)",
        ),
        meter.createAsyncUpDownCounter(
            "smithy.client.http.requests.limit",
            { it.record(_requestConcurrencyLimit.value) },
            "{request}",
            "Max concurrent requests configured for the HTTP client",
        ),
        meter.createAsyncUpDownCounter(
            "smithy.client.http.requests.usage",
            ::recordRequestsState,
            "{request}",
            "The current state of HTTP client request concurrency (queued, in-flight)",
        ),
        threadStateCallback?.let { callback ->
            meter.createAsyncUpDownCounter(
                "smithy.client.http.threads.count",
                { measurement -> recordThreadState(measurement, callback) },
                "{thread}",
                "Current state of HTTP engine threads (idle, active)",
            )
        },
    )

    /**
     * The maximum number of connections configured for the client
     */
    public var connectionsLimit: Long
        get() = _connectionsLimit.value
        set(value) {
            _connectionsLimit.update { value }
        }

    /**
     * The maximum number of concurrent requests configured for the client
     */
    public var requestConcurrencyLimit: Long
        get() = _requestConcurrencyLimit.value
        set(value) {
            _requestConcurrencyLimit.update { value }
        }

    /**
     * The number of idle (warm) connections in the pool right now
     */
    public var idleConnections: Long
        get() = _idleConnections.value
        set(value) {
            _idleConnections.update { value }
        }

    /**
     * The number of acquired (active) connections used right now
     */
    public var acquiredConnections: Long
        get() = _acquiredConnections.value
        set(value) {
            _acquiredConnections.update { value }
        }

    /**
     * The number of requests currently queued waiting to be dispatched/executed by the client
     */
    public var queuedRequests: Long
        get() = _queuedRequests.value
        set(value) {
            _queuedRequests.update { value }
        }

    /**
     * The number of requests currently in-flight (actively processing)
     */
    public var inFlightRequests: Long
        get() = _inFlightRequests.value
        set(value) {
            _inFlightRequests.update { value }
        }

    private fun recordRequestsState(measurement: LongAsyncMeasurement) {
        measurement.record(inFlightRequests, HttpClientMetricAttributes.InFlightRequest)
        measurement.record(queuedRequests, HttpClientMetricAttributes.QueuedRequest)
    }

    private fun recordConnectionState(measurement: LongAsyncMeasurement) {
        measurement.record(idleConnections, HttpClientMetricAttributes.IdleConnection)
        measurement.record(acquiredConnections, HttpClientMetricAttributes.AcquiredConnection)
    }

    private fun recordThreadState(measurement: LongAsyncMeasurement, callback: () -> ThreadState?) {
        callback()?.let { threadState ->
            measurement.record(threadState.idle, HttpClientMetricAttributes.IdleThread)
            measurement.record(threadState.active, HttpClientMetricAttributes.ActiveThread)
        }
    }

    override fun close() {
        val exceptions = asyncHandles
            .map { handle -> runCatching { handle.stop() } }
            .mapNotNull(Result<*>::exceptionOrNull)

        exceptions.firstOrNull()?.let { first ->
            exceptions.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }
}
