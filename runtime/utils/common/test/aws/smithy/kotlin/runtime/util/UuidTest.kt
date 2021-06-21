/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package aws.smithy.kotlin.runtime.util

import aws.smithy.kotlin.runtime.util.Uuid.WeakRng
import kotlin.test.Test
import kotlin.test.assertEquals

@ExperimentalUnsignedTypes
@OptIn(WeakRng::class)
class UuidTest {
    @Test
    fun `it should generate random UUIDs`() {
        repeat(100) { // randomness is unpredictable so do this a bunch to lower risk of false positive
            val uuid = Uuid.random()

            // Check that v4 flag is set
            assertEquals(0x00000000_0000_4000U.toLong(), uuid.high and 0x00000000_0000_f000U.toLong())

            // Check that type 2 is set
            assertEquals(0x8000_000000000000U.toLong(), uuid.low and 0xc000_000000000000U.toLong())
        }
    }

    @Test
    fun `it should yield valid UUID strings`() {
        val uuid = Uuid(0x12_34_56_78_90_ab_cd_ef, 0x21_43_65_87_09_ba_dc_fe)
        assertEquals("12345678-90ab-cdef-2143-658709badcfe", uuid.toString())
    }
}
