/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package software.aws.clientrt.http

import software.aws.clientrt.http.util.StringValuesMap
import software.aws.clientrt.http.util.StringValuesMapBuilder
import software.aws.clientrt.http.util.StringValuesMapImpl

/**
 * Immutable mapping of case insensitive HTTP header names to list of [String] values.
 */
interface Headers : StringValuesMap {
    companion object {
        operator fun invoke(block: HeadersBuilder.() -> Unit): Headers = HeadersBuilder()
            .apply(block).build()

        /**
         * Empty [Headers] instance
         */
        val Empty: Headers = EmptyHeaders
    }
}

private object EmptyHeaders : Headers {
    override val caseInsensitiveName: Boolean = true
    override fun getAll(name: String): List<String> = emptyList()
    override fun names(): Set<String> = emptySet()
    override fun entries(): Set<Map.Entry<String, List<String>>> = emptySet()
    override fun contains(name: String): Boolean = false
    override fun isEmpty(): Boolean = true
}

/**
 * Build an immutable HTTP header map
 */
class HeadersBuilder : StringValuesMapBuilder(true, 8) {
    override fun toString(): String = "HeadersBuilder ${entries()} "
    override fun build(): Headers {
        require(!built) { "HeadersBuilder can only build a single Headers instance" }
        built = true
        return HeadersImpl(values)
    }
}

private class HeadersImpl(
    values: Map<String, List<String>>
) : Headers, StringValuesMapImpl(true, values) {
    override fun toString(): String = "Headers ${entries()}"
}
