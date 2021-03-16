/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package software.aws.clientrt.http

import software.aws.clientrt.http.util.StringValuesMap
import software.aws.clientrt.http.util.StringValuesMapBuilder
import software.aws.clientrt.http.util.StringValuesMapImpl
import software.aws.clientrt.http.util.urlEncodeComponent

/**
 * Container for HTTP query parameters
 */
interface QueryParameters : StringValuesMap {
    companion object {
        operator fun invoke(block: QueryParametersBuilder.() -> Unit): QueryParameters = QueryParametersBuilder()
            .apply(block).build()

        /**
         * Empty [QueryParameters] instance
         */
        val Empty: QueryParameters = EmptyQueryParameters
    }
}

private object EmptyQueryParameters : QueryParameters {
    override val caseInsensitiveName: Boolean = true
    override fun getAll(name: String): List<String> = emptyList()
    override fun names(): Set<String> = emptySet()
    override fun entries(): Set<Map.Entry<String, List<String>>> = emptySet()
    override fun contains(name: String): Boolean = false
    override fun isEmpty(): Boolean = true
}

class QueryParametersBuilder : StringValuesMapBuilder(true, 8) {
    override fun build(): QueryParameters {
        require(!built) { "QueryParametersBuilder can only build a single instance" }
        built = true
        return QueryParametersImpl(values)
    }
}

private class QueryParametersImpl(values: Map<String, List<String>> = emptyMap()) : QueryParameters, StringValuesMapImpl(true, values) {
    override fun toString(): String = "QueryParameters ${entries()}"
}

/**
 * Return the encoded query parameter string (without leading '?')
 */
fun QueryParameters.urlEncode(): String = buildString {
    urlEncodeTo(this)
}

/**
 * URL encode the query parameters components to the appendable output (without the leading '?')
 */
fun QueryParameters.urlEncodeTo(out: Appendable) = urlEncodeQueryParametersTo(entries(), out)

internal fun urlEncodeQueryParametersTo(entries: Set<Map.Entry<String, List<String>>>, out: Appendable) {
    entries.sortedBy { it.key }.forEachIndexed { i, entry ->
        entry.value.forEachIndexed { j, value ->
            if (i > 0 || j > 0) {
                out.append("&")
            }
            out.append(entry.key)
            out.append("=")
            out.append(value.urlEncodeComponent())
        }
    }
}
