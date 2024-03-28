/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.smithy.kotlin.runtime.http.interceptors

import aws.smithy.kotlin.runtime.collections.get
import aws.smithy.kotlin.runtime.http.*
import aws.smithy.kotlin.runtime.http.HttpCall
import aws.smithy.kotlin.runtime.http.interceptors.FlexibleChecksumsResponseInterceptor.Companion.ChecksumHeaderValidated
import aws.smithy.kotlin.runtime.http.operation.*
import aws.smithy.kotlin.runtime.http.request.HttpRequestBuilder
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import aws.smithy.kotlin.runtime.httptest.TestEngine
import aws.smithy.kotlin.runtime.io.SdkSource
import aws.smithy.kotlin.runtime.io.source
import aws.smithy.kotlin.runtime.operation.ExecutionContext
import aws.smithy.kotlin.runtime.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.*

data class TestInput(val value: String)
data class TestOutput(val body: HttpBody)

inline fun <reified I> newTestOperation(serialized: HttpRequestBuilder): SdkHttpOperation<I, TestOutput> =
    SdkHttpOperation.build<I, TestOutput> {
        serializeWith = object : HttpSerializer.NonStreaming<I> {
            override fun serialize(context: ExecutionContext, input: I): HttpRequestBuilder = serialized
        }

        deserializeWith = object : HttpDeserializer.Streaming<TestOutput> {
            override suspend fun deserialize(context: ExecutionContext, call: HttpCall): TestOutput = TestOutput(call.response.body)
        }

        context {
            // required operation context
            operationName = "TestOperation"
            serviceName = "TestService"
        }
    }

class FlexibleChecksumsResponseInterceptorTest {

    private val response = "abc".repeat(1024).encodeToByteArray()

    private val checksums: List<Pair<String, String>> = listOf(
        "crc32c" to "wS3hug==",
        "crc32" to "UClbrQ==",
        "sha1" to "vwFegy8gsWrablgsmDmpvWqf1Yw=",
        "sha256" to "Z7AuR1ssOIhqbjhaKBn3S0hvPhIm27zu9jqT/1SMjNY=",
    )

    private fun getMockClient(response: ByteArray, responseHeaders: Headers = Headers.Empty): SdkHttpClient {
        val mockEngine = TestEngine { _, request ->
            val body = object : HttpBody.SourceContent() {
                override val contentLength: Long = response.size.toLong()
                override fun readFrom(): SdkSource = response.source()
                override val isOneShot: Boolean get() = false
            }

            val resp = HttpResponse(HttpStatusCode.OK, responseHeaders, body)

            HttpCall(request, resp, Instant.now(), Instant.now())
        }
        return SdkHttpClient(mockEngine)
    }

    @Test
    fun testResponseChecksumValid() = runTest {
        checksums.forEach { (checksumAlgorithmName, expectedChecksum) ->
            val req = HttpRequestBuilder()
            val op = newTestOperation<TestInput>(req)

            op.interceptors.add(
                FlexibleChecksumsResponseInterceptor<TestInput> {
                    true
                },
            )

            val responseChecksumHeaderName = "x-amz-checksum-$checksumAlgorithmName"

            val responseHeaders = Headers {
                append(responseChecksumHeaderName, expectedChecksum)
            }

            val client = getMockClient(response, responseHeaders)

            val output = op.roundTrip(client, TestInput("input"))
            output.body.readAll()
            assertEquals(responseChecksumHeaderName, op.context[ChecksumHeaderValidated])
        }
    }

    @Test
    fun testResponseServiceChecksumInvalid() = runTest {
        checksums.forEach { (checksumAlgorithmName, _) ->
            val req = HttpRequestBuilder()
            val op = newTestOperation<TestInput>(req)

            op.interceptors.add(
                FlexibleChecksumsResponseInterceptor<TestInput> {
                    true
                },
            )

            val responseChecksumHeaderName = "x-amz-checksum-$checksumAlgorithmName"

            val responseHeaders = Headers {
                append(responseChecksumHeaderName, "incorrect-$checksumAlgorithmName-checksum-from-service")
            }
            val client = getMockClient(response, responseHeaders)

            assertFailsWith<ChecksumMismatchException> {
                val output = op.roundTrip(client, TestInput("input"))
                output.body.readAll()
            }

            assertEquals(op.context[ChecksumHeaderValidated], responseChecksumHeaderName)
        }
    }

    @Test
    fun testMultipleChecksumsReturned() = runTest {
        val req = HttpRequestBuilder()
        val op = newTestOperation<TestInput>(req)

        op.interceptors.add(
            FlexibleChecksumsResponseInterceptor<TestInput> {
                true
            },
        )

        val responseHeaders = Headers {
            append("x-amz-checksum-crc32c", "wS3hug==")
            append("x-amz-checksum-sha1", "vwFegy8gsWrablgsmDmpvWqf1Yw=")
            append("x-amz-checksum-crc32", "UClbrQ==")
        }

        val client = getMockClient(response, responseHeaders)
        op.roundTrip(client, TestInput("input"))

        // CRC32C validation should be prioritized
        assertEquals("x-amz-checksum-crc32c", op.context[ChecksumHeaderValidated])
    }

    @Test
    fun testSkipsValidationOfMultipartChecksum() = runTest {
        val req = HttpRequestBuilder()
        val op = newTestOperation<TestInput>(req)

        op.interceptors.add(
            FlexibleChecksumsResponseInterceptor<TestInput> {
                true
            },
        )

        val responseHeaders = Headers {
            append("x-amz-checksum-crc32c-1", "incorrect-checksum-would-throw-if-validated")
        }

        val client = getMockClient(response, responseHeaders)

        op.roundTrip(client, TestInput("input"))
    }

    @Test
    fun testSkipsValidationWhenDisabled() = runTest {
        val req = HttpRequestBuilder()
        val op = newTestOperation<TestInput>(req)

        op.interceptors.add(
            FlexibleChecksumsResponseInterceptor<TestInput> {
                false
            },
        )

        val responseChecksumHeaderName = "x-amz-checksum-crc32"

        val responseHeaders = Headers {
            append(responseChecksumHeaderName, "incorrect-checksum-would-throw-if-validated")
        }

        val client = getMockClient(response, responseHeaders)

        val output = op.roundTrip(client, TestInput("input"))
        output.body.readAll()

        assertNull(op.context.getOrNull(ChecksumHeaderValidated))
    }
}
