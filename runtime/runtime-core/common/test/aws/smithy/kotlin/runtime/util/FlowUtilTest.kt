/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FlowUtilTest {
    @Test
    fun testMergingFlows() = runTest {
        val a = flowOf(1)
        val b = flowOf(2, 3, 4)
        val merged = mergeSequential(a, b).toList()
        assertEquals(listOf(1, 2, 3, 4), merged)
    }

    @Test
    fun testMergingEmptyFlow() = runTest {
        val a: Flow<Int> = flowOf()
        val b: Flow<Int> = flowOf(4, 5, 6)

        val merged = mergeSequential(a, b).toList()
        assertEquals(listOf(4, 5, 6), merged)
    }

    @Test
    fun testMergingOneFlow() = runTest {
        val a = flowOf(1, 2, 3)
        val merged = mergeSequential(a).toList()

        assertEquals(listOf(1, 2, 3), merged)
    }

    @Test
    fun testMergingSameFlow() = runTest {
        val a = flowOf(1, 2, 3)
        val merged = mergeSequential(a, a).toList()
        assertEquals(listOf(1, 2, 3, 1, 2, 3), merged)
    }

    @Test
    fun testMergingMoreThanTwoFlows() = runTest {
        val a = flowOf(1)
        val b = flowOf(2)
        val c = flowOf(3)

        val merged = mergeSequential(a, b, c).toList()
        assertEquals(listOf(1, 2, 3), merged)
    }
}
