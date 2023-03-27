/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.http.auth

import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.auth.awssigning.AwsSigner
import aws.smithy.kotlin.runtime.auth.awssigning.AwsSigningAttributes
import aws.smithy.kotlin.runtime.auth.awssigning.DefaultAwsSigner
import aws.smithy.kotlin.runtime.auth.awssigning.crt.CrtAwsSigner
import aws.smithy.kotlin.runtime.auth.awssigning.internal.AWS_CHUNKED_THRESHOLD
import aws.smithy.kotlin.runtime.http.*
import aws.smithy.kotlin.runtime.http.content.ByteArrayContent
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngineBase
import aws.smithy.kotlin.runtime.http.operation.*
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.http.request.HttpRequestBuilder
import aws.smithy.kotlin.runtime.http.response.HttpCall
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import aws.smithy.kotlin.runtime.identity.asIdentityProviderConfig
import aws.smithy.kotlin.runtime.io.SdkByteReadChannel
import aws.smithy.kotlin.runtime.net.Host
import aws.smithy.kotlin.runtime.net.Scheme
import aws.smithy.kotlin.runtime.operation.ExecutionContext
import aws.smithy.kotlin.runtime.time.Instant
import aws.smithy.kotlin.runtime.util.Attributes
import aws.smithy.kotlin.runtime.util.get
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultAwsHttpSignerTest : AwsHttpSignerTestBase(DefaultAwsSigner)
class CrtAwsHttpSignerTest : AwsHttpSignerTestBase(CrtAwsSigner)

/**
 * Basic sanity tests. Signing (including `AwsHttpSigner`) is covered by the more exhaustive
 * test suite in the `aws-signing-tests` module.
 */
@Suppress("HttpUrlsUsage")
@OptIn(ExperimentalCoroutinesApi::class)
public abstract class AwsHttpSignerTestBase(
    private val signer: AwsSigner,
) {
    private val testCredentials = Credentials("AKID", "SECRET", "SESSION")

    private fun buildOperation(
        requestBody: String = "{\"TableName\": \"foo\"}",
        streaming: Boolean = false,
        replayable: Boolean = true,
        unsigned: Boolean = false,
    ): SdkHttpOperation<Unit, HttpResponse> {
        val operation: SdkHttpOperation<Unit, HttpResponse> = SdkHttpOperation.build {
            serializer = object : HttpSerialize<Unit> {
                override suspend fun serialize(context: ExecutionContext, input: Unit): HttpRequestBuilder =
                    HttpRequestBuilder().apply {
                        method = HttpMethod.POST
                        url.scheme = Scheme.HTTP
                        url.host = Host.Domain("demo.us-east-1.amazonaws.com")
                        url.path = "/"
                        headers.append("Host", "demo.us-east-1.amazonaws.com")
                        headers.appendAll("x-amz-archive-description", listOf("test", "test"))
                        body = when (streaming) {
                            true -> {
                                object : HttpBody.ChannelContent() {
                                    override val contentLength: Long = requestBody.length.toLong()
                                    override fun readFrom(): SdkByteReadChannel = SdkByteReadChannel(requestBody.encodeToByteArray())
                                    override val isOneShot: Boolean = !replayable
                                }
                            }
                            false -> ByteArrayContent(requestBody.encodeToByteArray())
                        }
                        headers.append("Content-Length", body.contentLength?.toString() ?: "0")
                    }
            }
            deserializer = IdentityDeserializer

            context {
                operationName = "testSigningOperation"
                service = "TestService"
                set(AwsSigningAttributes.SigningRegion, "us-east-1")
                set(AwsSigningAttributes.SigningDate, Instant.fromIso8601("2020-10-16T19:56:00Z"))
                set(AwsSigningAttributes.SigningService, "demo")
            }
        }

        val idp = object : CredentialsProvider {
            override suspend fun resolve(attributes: Attributes): Credentials = testCredentials
        }

        val signerConfig = AwsHttpSigner.Config().apply {
            signer = this@AwsHttpSignerTestBase.signer
            service = "demo"
            isUnsignedPayload = unsigned
        }

        operation.execution.auth = OperationAuthConfig.from(idp.asIdentityProviderConfig(), SigV4AuthScheme(signerConfig))

        return operation
    }

    private suspend fun getSignedRequest(
        operation: SdkHttpOperation<Unit, HttpResponse>,
    ): HttpRequest {
        val mockEngine = object : HttpClientEngineBase("test") {
            override suspend fun roundTrip(context: ExecutionContext, request: HttpRequest): HttpCall {
                val now = Instant.now()
                val resp = HttpResponse(HttpStatusCode.fromValue(200), Headers.Empty, HttpBody.Empty)
                return HttpCall(request, resp, now, now)
            }
        }
        val client = SdkHttpClient(mockEngine)

        operation.roundTrip(client, Unit)
        return operation.context[HttpOperationContext.HttpCallList].last().request
    }

    @Test
    public fun testSignRequest(): TestResult = runTest {
        val op = buildOperation()
        val expectedDate = "20201016T195600Z"
        val expectedSig = "AWS4-HMAC-SHA256 Credential=AKID/20201016/us-east-1/demo/aws4_request, " +
            "SignedHeaders=content-length;host;x-amz-archive-description;x-amz-date;x-amz-security-token, " +
            "Signature=e60a4adad4ae15e05c96a0d8ac2482fbcbd66c88647c4457db74e4dad1648608"

        val signed = getSignedRequest(op)
        assertEquals(expectedDate, signed.headers["X-Amz-Date"])
        assertEquals(expectedSig, signed.headers["Authorization"])
    }

    @Test
    public fun testUnsignedRequest(): TestResult = runTest {
        val op = buildOperation(unsigned = true)
        val expectedDate = "20201016T195600Z"
        val expectedSig = "AWS4-HMAC-SHA256 Credential=AKID/20201016/us-east-1/demo/aws4_request, " +
            "SignedHeaders=content-length;host;x-amz-archive-description;x-amz-date;x-amz-security-token, " +
            "Signature=6c0cc11630692e2c98f28003c8a0349b56011361e0bab6545f1acee01d1d211e"

        val signed = getSignedRequest(op)
        assertEquals(expectedDate, signed.headers["X-Amz-Date"])
        assertEquals(expectedSig, signed.headers["Authorization"])
    }

    @Test
    public fun testSignReplayableStreamingRequest(): TestResult = runTest {
        val op = buildOperation(streaming = true)
        val expectedDate = "20201016T195600Z"
        val expectedSig = "AWS4-HMAC-SHA256 Credential=AKID/20201016/us-east-1/demo/aws4_request, " +
            "SignedHeaders=content-length;host;x-amz-archive-description;x-amz-date;x-amz-security-token, " +
            "Signature=e60a4adad4ae15e05c96a0d8ac2482fbcbd66c88647c4457db74e4dad1648608"

        val signed = getSignedRequest(op)
        assertEquals(expectedDate, signed.headers["X-Amz-Date"])
        assertEquals(expectedSig, signed.headers["Authorization"])
    }

    @Test
    public fun testSignAwsChunkedStreamNonReplayable(): TestResult = runTest {
        val op = buildOperation(streaming = true, replayable = false, requestBody = "a".repeat(AWS_CHUNKED_THRESHOLD + 1))
        val expectedDate = "20201016T195600Z"
        val expectedSig = "AWS4-HMAC-SHA256 Credential=AKID/20201016/us-east-1/demo/aws4_request, " +
            "SignedHeaders=content-encoding;content-length;host;transfer-encoding;x-amz-archive-description;x-amz-date;x-amz-decoded-content-length;x-amz-security-token, " +
            "Signature=dec1a06b61f953afe430ce4a0f10ee8d5ad3d29696516c4ccda23a0aab6664d5"

        val signed = getSignedRequest(op)
        assertEquals(expectedDate, signed.headers["X-Amz-Date"])
        assertEquals(expectedSig, signed.headers["Authorization"])
    }

    @Test
    public fun testSignAwsChunkedStreamReplayable(): TestResult = runTest {
        val op = buildOperation(streaming = true, replayable = true, requestBody = "a".repeat(AWS_CHUNKED_THRESHOLD + 1))
        val expectedDate = "20201016T195600Z"
        val expectedSig = "AWS4-HMAC-SHA256 Credential=AKID/20201016/us-east-1/demo/aws4_request, " +
            "SignedHeaders=content-encoding;content-length;host;transfer-encoding;x-amz-archive-description;x-amz-date;x-amz-decoded-content-length;x-amz-security-token, " +
            "Signature=dec1a06b61f953afe430ce4a0f10ee8d5ad3d29696516c4ccda23a0aab6664d5"

        val signed = getSignedRequest(op)
        assertEquals(expectedDate, signed.headers["X-Amz-Date"])
        assertEquals(expectedSig, signed.headers["Authorization"])
    }

    @Test
    public fun testSignOneShotStream(): TestResult = runTest {
        val op = buildOperation(streaming = true, replayable = false)
        val expectedDate = "20201016T195600Z"
        // should have same signature as testSignAwsChunkedStreamNonReplayable(), except for the hash, since the body is different
        val expectedSig = "AWS4-HMAC-SHA256 Credential=AKID/20201016/us-east-1/demo/aws4_request, " +
            "SignedHeaders=content-encoding;content-length;host;transfer-encoding;x-amz-archive-description;x-amz-date;x-amz-decoded-content-length;x-amz-security-token, " +
            "Signature=9600a7fbf17056d41557ec5d6abfe7b5db4a75222e563f5e16afde9c1c0014bb"

        val signed = getSignedRequest(op)
        assertEquals(expectedDate, signed.headers["X-Amz-Date"])
        assertEquals(expectedSig, signed.headers["Authorization"])
    }
}