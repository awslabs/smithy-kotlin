/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueryParametersTest {

    @Test
    fun itBuilds() {
        val params = QueryParameters {
            append("foo", "baz")
            appendAll("foo", listOf("bar", "quux"))
            append("qux", "john")
            remove("qux")
        }
        assertEquals(params.getAll("foo"), listOf("baz", "bar", "quux"))
        assertTrue(params.contains("foo", "quux"))
        assertFalse(params.contains("qux"))
        params.forEach { name, values ->
            when (name) {
                "foo" -> assertEquals(values, listOf("baz", "bar", "quux"))
            }
        }
    }

    @Test
    fun itEncodesToQueryString() {
        data class QueryParamTest(val params: QueryParameters, val expected: String)
        val tests: List<QueryParamTest> = listOf(
            QueryParamTest(
                QueryParameters {
                    append("q", "puppies")
                    append("oe", "utf8")
                },
                "oe=utf8&q=puppies",
            ),
            QueryParamTest(
                QueryParameters {
                    appendAll("q", listOf("dogs", "&", "7"))
                },
                "q=dogs&q=%26&q=7",
            ),
            QueryParamTest(
                QueryParameters {
                    appendAll("a", listOf("a1", "a2", "a3"))
                    appendAll("b", listOf("b1", "b2", "b3"))
                    appendAll("c", listOf("c1", "c2", "c3"))
                },
                "a=a1&a=a2&a=a3&b=b1&b=b2&b=b3&c=c1&c=c2&c=c3",
            ),
        )
        for (test in tests) {
            val actual = test.params.urlEncode()
            assertEquals(test.expected, actual, "expected ${test.expected}; got: $actual")
        }
    }

    @Test
    fun testSubsequentModificationsDontAffectOriginal() {
        val builder = QueryParametersBuilder()

        builder.append("a", "alligator")
        builder.append("b", "bunny")
        builder.append("c", "chinchilla")
        val first = builder.build()
        val firstExpected = mapOf(
            "a" to listOf("alligator"),
            "b" to listOf("bunny"),
            "c" to listOf("chinchilla"),
        )

        builder.append("a", "anteater")
        builder.remove("b")
        builder["c"] = "crocodile"
        val second = builder.build()
        val secondExpected = mapOf(
            "a" to listOf("alligator", "anteater"),
            "c" to listOf("crocodile"),
        )

        assertEquals(firstExpected.entries, first.entries())
        assertEquals(secondExpected.entries, second.entries())
    }
}
