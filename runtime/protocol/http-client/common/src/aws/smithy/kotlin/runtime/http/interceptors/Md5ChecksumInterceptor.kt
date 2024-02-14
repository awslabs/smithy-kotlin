/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.smithy.kotlin.runtime.http.interceptors

import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.client.ProtocolRequestInterceptorContext
import aws.smithy.kotlin.runtime.hashing.md5
import aws.smithy.kotlin.runtime.http.HttpBody
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.http.request.header
import aws.smithy.kotlin.runtime.http.request.toBuilder
import aws.smithy.kotlin.runtime.text.encoding.encodeBase64String

/**
 * Set the `Content-MD5` header based on the current payload
 * See:
 *   - https://awslabs.github.io/smithy/1.0/spec/core/behavior-traits.html#httpchecksumrequired-trait
 *   - https://datatracker.ietf.org/doc/html/rfc1864.html
 * @param block An optional function which parses the input [I] to determine if the `Content-MD5` header should be set.
 * If not provided, the default behavior will set the header.
 */
@InternalApi
public class Md5ChecksumInterceptor<I>(
    private val block: ((input: I) -> Boolean)? = null,
) : HttpInterceptor {
    private var cachedChecksum: String? = null

    override suspend fun modifyBeforeSigning(context: ProtocolRequestInterceptorContext<Any, HttpRequest>): HttpRequest {
        @Suppress("UNCHECKED_CAST")
        val input = context.request as I

        val injectMd5Header = block?.invoke(input) ?: true

        if (!injectMd5Header) {
            return context.protocolRequest
        }

        val checksum = cachedChecksum ?: run {
            when (val body = context.protocolRequest.body) {
                is HttpBody.Bytes -> body.bytes().md5().encodeBase64String().also { cachedChecksum = it }
                else -> null
            }
        }

        return checksum?.let {
            val req = context.protocolRequest.toBuilder()
            req.header("Content-MD5", it)
            req.build()
        } ?: context.protocolRequest
    }
}
