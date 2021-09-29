/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package aws.smithy.kotlin.runtime.httptest

import aws.smithy.kotlin.runtime.http.Headers
import aws.smithy.kotlin.runtime.http.HttpBody
import aws.smithy.kotlin.runtime.http.HttpStatusCode
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngineBase
import aws.smithy.kotlin.runtime.http.engine.callContext
import aws.smithy.kotlin.runtime.http.readAll
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.http.request.HttpRequestBuilder
import aws.smithy.kotlin.runtime.http.response.HttpCall
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import aws.smithy.kotlin.runtime.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An expected HttpRequest with the response that should be returned by the engine
 */
data class ExpectedHttpRequest(val request: HttpRequest, val response: HttpResponse? = null)

/**
 * Actual and expected [HttpRequest] pair
 */
data class CapturedRequest(val expected: HttpRequest, val actual: HttpRequest) {
    /**
     * Assert that all of the components set on [expected] are also the same on [actual]. The actual request
     * may have additional headers, only the ones set in [expected] are compared.
     */
    internal suspend fun assertRequest(idx: Int) {
        assertEquals(expected.url.toString(), actual.url.toString(), "[request#$idx]: URL mismatch")
        expected.headers.forEach { name, values ->
            values.forEach {
                assertTrue(actual.headers.contains(name, it), "[request#$idx]: header $name missing value $it")
            }
        }

        val expectedBody = expected.body.readAll()?.decodeToString()
        val actualBody = actual.body.readAll()?.decodeToString()
        assertEquals(expectedBody, actualBody, "[request#$idx]: body mismatch")
    }
}

/**
 * TestConnection implements [aws.smithy.kotlin.runtime.http.engine.HttpClientEngine] with canned responses.
 * For each expected request it will capture the actual and respond with the pre-configured response (or a basic 200-OK
 * with an empty body if none was configured).
 *
 * After all requests/responses have been made use [assertRequests] to test that the actual requests captured match
 * the expected.
 *
 * NOTE: This engine is only capable of modeling request/response pairs. More complicated interactions such as duplex
 * streaming are not implemented.
 */
class TestConnection(expected: List<ExpectedHttpRequest> = emptyList()) : HttpClientEngineBase("TestConnection") {
    private val expected = expected.toMutableList()
    // expected is mutated in-flight, store original size
    private val expectedCount = expected.size
    private var captured = mutableListOf<CapturedRequest>()

    override suspend fun roundTrip(request: HttpRequest): HttpCall {
        val next = expected.removeFirstOrNull() ?: error("TestConnection has no remaining expected requests")
        captured.add(CapturedRequest(next.request, request))

        val response = next.response ?: HttpResponse(HttpStatusCode.OK, Headers.Empty, HttpBody.Empty)
        val now = Instant.now()
        return HttpCall(request, response, now, now, callContext())
    }

    /**
     * Get the list of captured requests so far
     */
    fun requests(): List<CapturedRequest> = captured

    /**
     * Assert that each captured request matches the expected
     */
    suspend fun assertRequests() {
        assertEquals(expectedCount, captured.size)
        captured.forEachIndexed { idx, captured ->
            captured.assertRequest(idx)
        }
    }
}

/**
 * DSL builder for [TestConnection]
 */
class HttpTestConnectionBuilder {
    val requests = mutableListOf<ExpectedHttpRequest>()

    class HttpRequestResponsePairBuilder {
        internal val requestBuilder = HttpRequestBuilder()
        var response: HttpResponse? = null
        fun request(block: HttpRequestBuilder.() -> Unit) = requestBuilder.apply(block)
    }

    fun expect(block: HttpRequestResponsePairBuilder.() -> Unit) {
        val builder = HttpRequestResponsePairBuilder().apply(block)
        requests.add(ExpectedHttpRequest(builder.requestBuilder.build(), builder.response))
    }

    fun expect(request: HttpRequest, response: HttpResponse? = null) {
        requests.add(ExpectedHttpRequest(request, response))
    }
}

/**
 * Invoke [block] with the given builder and construct a new [TestConnection]
 *
 * Example:
 * ```kotlin
 * val testEngine = buildTestConnection {
 *     expect {
 *         request {
 *             url.host = "myhost"
 *             headers.append("x-foo", "bar")
 *         }
 *         response = HttpResponse(HttpStatusCode.OK, Headers.Empty, HttpBody.Empty)
 *     }
 * }
 * ```
 */
fun buildTestConnection(block: HttpTestConnectionBuilder.() -> Unit): TestConnection {
    val builder = HttpTestConnectionBuilder().apply(block)
    return TestConnection(builder.requests)
}
