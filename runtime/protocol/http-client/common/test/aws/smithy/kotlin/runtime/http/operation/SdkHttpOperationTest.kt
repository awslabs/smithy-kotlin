/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.smithy.kotlin.runtime.http.operation

import aws.smithy.kotlin.runtime.http.SdkHttpClient
import aws.smithy.kotlin.runtime.http.request.HttpRequestBuilder
import aws.smithy.kotlin.runtime.httptest.TestEngine
import aws.smithy.kotlin.runtime.telemetry.logging.loggingContext
import aws.smithy.kotlin.runtime.telemetry.trace.traceSpan
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class SdkHttpOperationTest {
    @Test
    fun testTelemetryInstrumentation() = runTest {
        val op = newTestOperation<Unit, Unit>(HttpRequestBuilder(), Unit)
        val client = SdkHttpClient(TestEngine())
        op.execute(client, Unit) {
            val cc = kotlin.coroutines.coroutineContext
            // test certain elements are setup correctly
            assertEquals("TestService.TestOperation", cc.loggingContext["rpc"])
            assertNotNull(cc.loggingContext["sdkInvocationId"])
            assertNotNull(cc.traceSpan)
        }
    }

    @Test
    fun testMissingRequiredProperties() = runTest {
        val ex = assertFailsWith<IllegalArgumentException> {
            @Suppress("DEPRECATION")
            SdkHttpOperation.build<Unit, Unit> {
                serializer = UnitSerializer
                deserializer = UnitDeserializer
            }
        }

        ex.message.shouldContain("operationName is a required HTTP execution attribute")
    }
}
