/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.smithy.kotlin.runtime.awsprotocol.json

import aws.smithy.kotlin.runtime.collections.get
import aws.smithy.kotlin.runtime.http.*
import aws.smithy.kotlin.runtime.http.operation.*
import aws.smithy.kotlin.runtime.http.request.HttpRequestBuilder
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import aws.smithy.kotlin.runtime.httptest.TestEngine
import aws.smithy.kotlin.runtime.operation.ExecutionContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AwsJsonProtocolTest {

    @Test
    fun testSetJsonProtocolHeaders() = runTest {
        @Suppress("DEPRECATION")
        val op = SdkHttpOperation.build<Unit, HttpResponse> {
            serializer = UnitSerializer
            deserializer = IdentityDeserializer
            operationName = "Bar"
            serviceName = "Foo"
        }
        val client = SdkHttpClient(TestEngine())
        val m = AwsJsonProtocol("FooService_blah", "1.1")
        op.install(m)

        op.roundTrip(client, Unit)
        val request = op.context[HttpOperationContext.HttpCallList].last().request

        assertEquals("application/x-amz-json-1.1", request.headers["Content-Type"])
        // ensure we use the original shape id name, NOT the one from the context
        // see: https://github.com/awslabs/smithy-kotlin/issues/316
        assertEquals("FooService_blah.Bar", request.headers["X-Amz-Target"])
    }

    @Test
    fun testEmptyBody() = runTest {
        @Suppress("DEPRECATION")
        val op = SdkHttpOperation.build<Unit, HttpResponse> {
            serializer = UnitSerializer
            deserializer = IdentityDeserializer
            operationName = "Bar"
            serviceName = "Foo"
        }
        val client = SdkHttpClient(TestEngine())
        op.install(AwsJsonProtocol("FooService", "1.1"))

        op.roundTrip(client, Unit)
        val request = op.context[HttpOperationContext.HttpCallList].last().request
        val actual = request.body.readAll()?.decodeToString()

        assertEquals("{}", actual)
    }

    @Test
    fun testDoesNotOverride() = runTest {
        @Suppress("DEPRECATION")
        val op = SdkHttpOperation.build<Unit, HttpResponse> {
            serializer = object : HttpSerialize<Unit> {
                override suspend fun serialize(context: ExecutionContext, input: Unit): HttpRequestBuilder =
                    HttpRequestBuilder().apply {
                        headers["Content-Type"] = "application/xml"
                        body = HttpBody.fromBytes("foo".encodeToByteArray())
                    }
            }
            deserializer = IdentityDeserializer
            operationName = "Bar"
            serviceName = "Foo"
        }
        val client = SdkHttpClient(TestEngine())
        op.install(AwsJsonProtocol("FooService", "1.1"))

        op.roundTrip(client, Unit)
        val request = op.context[HttpOperationContext.HttpCallList].last().request
        val actual = request.body.readAll()?.decodeToString()
        assertEquals("application/xml", request.headers["Content-Type"])
        assertEquals("foo", actual)
    }
}
