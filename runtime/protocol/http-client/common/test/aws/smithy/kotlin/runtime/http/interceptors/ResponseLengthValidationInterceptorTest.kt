/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.http.interceptors

import aws.smithy.kotlin.runtime.http.*
import aws.smithy.kotlin.runtime.http.HttpCall
import aws.smithy.kotlin.runtime.http.operation.*
import aws.smithy.kotlin.runtime.http.request.HttpRequestBuilder
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import aws.smithy.kotlin.runtime.httptest.TestEngine
import aws.smithy.kotlin.runtime.io.EOFException
import aws.smithy.kotlin.runtime.io.SdkByteReadChannel
import aws.smithy.kotlin.runtime.io.SdkSource
import aws.smithy.kotlin.runtime.io.source
import aws.smithy.kotlin.runtime.operation.ExecutionContext
import aws.smithy.kotlin.runtime.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.*

object ResponseLengthValidationTestInput
data class ResponseLengthValidationTestOutput(val body: HttpBody)

private const val CONTENT_LENGTH_HEADER_NAME = "Content-Length"
private val RESPONSE = "a".repeat(500).encodeToByteArray()

fun op() =
    SdkHttpOperation.build<ResponseLengthValidationTestInput, ResponseLengthValidationTestOutput> {
        serializeWith = object : HttpSerializer.NonStreaming<ResponseLengthValidationTestInput> {
            override fun serialize(
                context: ExecutionContext,
                input: ResponseLengthValidationTestInput,
            ): HttpRequestBuilder = HttpRequestBuilder()
        }

        deserializeWith = object : HttpDeserializer.Streaming<ResponseLengthValidationTestOutput> {
            override suspend fun deserialize(
                context: ExecutionContext,
                call: HttpCall,
            ): ResponseLengthValidationTestOutput = ResponseLengthValidationTestOutput(call.response.body)
        }

        operationName = "TestOperation"
        serviceName = "TestService"
    }.also {
        it.interceptors.add(ResponseLengthValidationInterceptor())
    }

private fun client(body: HttpBody, expectedContentSize: Int?): SdkHttpClient {
    val headers = Headers {
        expectedContentSize?.let { append(CONTENT_LENGTH_HEADER_NAME, it.toString()) }
    }

    val engine = TestEngine { _, request ->
        val resp = HttpResponse(HttpStatusCode.OK, headers, body)
        HttpCall(request, resp, Instant.now(), Instant.now())
    }

    return SdkHttpClient(engine)
}

class ResponseLengthValidationInterceptorTest {
    private fun nonEmptyBodies() = listOf(
        object : HttpBody.SourceContent() {
            override val contentLength: Long = RESPONSE.size.toLong()
            override fun readFrom(): SdkSource = RESPONSE.source()
            override val isOneShot = false
        },
        object : HttpBody.ChannelContent() {
            override val contentLength: Long = RESPONSE.size.toLong()
            override fun readFrom() = SdkByteReadChannel(RESPONSE)
            override val isOneShot = false
        },
        HttpBody.fromBytes(RESPONSE),
    )

    private fun allBodies() = nonEmptyBodies() + HttpBody.Empty

    @Test
    fun testCorrectLengthReturned() = runTest {
        nonEmptyBodies().forEach { body ->
            val client = client(body, RESPONSE.size) // expect correct content length
            val output = op().roundTrip(client, ResponseLengthValidationTestInput)
            output.body.readAll()
        }
    }

    @Test
    fun testNotEnoughBytesReturned() = runTest {
        nonEmptyBodies().forEach { body ->
            val client = client(body, RESPONSE.size * 2) // expect double the actual content length
            assertFailsWith<EOFException> {
                val output = op().roundTrip(client, ResponseLengthValidationTestInput)
                output.body.readAll()
            }
        }
    }

    @Test
    fun testTooManyBytesReturned() = runTest {
        allBodies().forEach { body ->
            val client = client(body, RESPONSE.size / 2) // expect half the actual content length
            assertFailsWith<EOFException> {
                val output = op().roundTrip(client, ResponseLengthValidationTestInput)
                output.body.readAll()
            }
        }
    }

    @Test
    fun testNoContentLengthSkipsValidation() = runTest {
        allBodies().forEach { body ->
            val client = client(body, null) // no content-length header so no validation
            val output = op().roundTrip(client, ResponseLengthValidationTestInput)
            output.body.readAll()
        }
    }

    @Test
    fun testEmptyBodyCorrectLengthReturned() = runTest {
        val client = client(HttpBody.Empty, 0) // expect correct content length
        val output = op().roundTrip(client, ResponseLengthValidationTestInput)
        output.body.readAll()
    }
}
