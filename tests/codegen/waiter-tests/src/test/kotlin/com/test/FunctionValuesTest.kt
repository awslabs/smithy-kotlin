/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.test

import aws.smithy.kotlin.runtime.retries.Outcome
import aws.smithy.kotlin.runtime.retries.getOrThrow
import com.test.model.EntityPrimitives
import com.test.model.GetFunctionValuesEqualsRequest
import com.test.model.GetFunctionValuesEqualsResponse
import com.test.model.Values
import com.test.waiters.waitUntilValuesFunctionPrimitivesStringEquals
import com.test.waiters.waitUntilValuesFunctionSampleValuesEquals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FunctionValuesTest {
    private fun successTest(
        block: suspend WaitersTestClient.(request: GetFunctionValuesEqualsRequest) -> Outcome<GetFunctionValuesEqualsResponse>,
        vararg results: GetFunctionValuesEqualsResponse,
    ): Unit = runTest {
        val client = DefaultWaitersTestClient(results.map { Result.success(it) })
        val req = GetFunctionValuesEqualsRequest { name = "test" }

        val outcome = client.block(req)
        assertEquals(results.size, outcome.attempts)
        assertEquals(results.last(), outcome.getOrThrow())
    }

    @Test
    fun testValuesFunctionPrimitivesStringEquals() = successTest(
        WaitersTestClient::waitUntilValuesFunctionPrimitivesStringEquals,
        GetFunctionValuesEqualsResponse { primitives = EntityPrimitives { string = "baz" } },
        GetFunctionValuesEqualsResponse { primitives = EntityPrimitives { string = "foo" } },
    )

    @Test
    fun testValuesFunctionSampleValuesEquals() = successTest(
        WaitersTestClient::waitUntilValuesFunctionSampleValuesEquals,
        GetFunctionValuesEqualsResponse {
            sampleValues = Values {
                valueOne = "baz"
                valueTwo = "baz"
                valueThree = "baz"
            }
        },
        GetFunctionValuesEqualsResponse {
            sampleValues = Values {
                valueOne = "foo"
                valueTwo = "foo"
                valueThree = "foo"
            }
        },
    )
}
