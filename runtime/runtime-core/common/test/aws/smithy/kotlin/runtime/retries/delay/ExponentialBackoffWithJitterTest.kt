/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.smithy.kotlin.runtime.retries.delay

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ExponentialBackoffWithJitterTest {
    @Test
    fun testScaling() = runTest {
        val delayer = ExponentialBackoffWithJitter {
            initialDelay = 10.milliseconds
            scaleFactor = 2.0 // Make the numbers easy for tests
            jitter = 0.0 // Disable jitter for this test
            maxBackoff = Duration.INFINITE // Effectively disable max backoff
        }
        assertEquals(listOf(10, 20, 40, 80, 160, 320), backoffSeries(6, delayer))
    }

    @Test
    fun testJitter() = runTest {
        val delayer = ExponentialBackoffWithJitter {
            initialDelay = 10.milliseconds
            scaleFactor = 2.0 // Make the numbers easy for tests
            jitter = 0.6 // 60% jitter for this test
            maxBackoff = Duration.INFINITE // Effectively disable max backoff
        }
        backoffSeries(6, delayer)
            .zip(listOf(4..10, 8..20, 16..40, 32..80, 64..160, 128..320))
            .forEach { (actualMs, rangeMs) ->
                assertTrue(actualMs in rangeMs, "Actual ms $actualMs was not in expected range $rangeMs")
            }
    }

    @Test
    fun testMaxBackoff() = runTest {
        val delayer = ExponentialBackoffWithJitter {
            initialDelay = 10.milliseconds
            scaleFactor = 2.0 // Make the numbers easy for tests
            jitter = 0.0 // Disable jitter for this test
            maxBackoff = 100.milliseconds
        }
        assertEquals(listOf(10, 20, 40, 80, 100, 100), backoffSeries(6, delayer))
    }
}

private suspend fun TestScope.backoffSeries(times: Int, delayer: ExponentialBackoffWithJitter): List<Int> =
    (1..times)
        .map { idx -> measure { delayer.backoff(idx) } }
        .map { it.first } // Just need the timing, not the results
