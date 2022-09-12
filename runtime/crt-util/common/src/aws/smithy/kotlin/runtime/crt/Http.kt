/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.smithy.kotlin.runtime.crt

import aws.sdk.kotlin.crt.http.HttpRequestBodyStream
import aws.smithy.kotlin.runtime.http.*
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.http.request.HttpRequestBuilder
import aws.smithy.kotlin.runtime.http.util.splitAsQueryParameters
import aws.smithy.kotlin.runtime.util.InternalApi
import kotlin.coroutines.coroutineContext
import aws.sdk.kotlin.crt.http.Headers as HeadersCrt
import aws.sdk.kotlin.crt.http.HttpRequest as HttpRequestCrt

/**
 * Convert an [HttpRequestBuilder] into a CRT HttpRequest for the purpose of signing.
 */
@InternalApi
public suspend fun HttpRequestBuilder.toSignableCrtRequest(unsignedPayload: Boolean = false): HttpRequestCrt {
    // Streams that implement HttpBody.Streaming and are not replayable are not signable without consuming the stream
    // and would need to go through chunked signing or unsigned payload
    // see: https://github.com/awslabs/smithy-kotlin/issues/296

    val bodyStream = if (!unsignedPayload) {
        signableBodyStream(body)
    } else {
        null
    }

    return HttpRequestCrt(method.name, url.encodedPath, HttpHeadersCrt(headers), bodyStream)
}

private suspend fun signableBodyStream(body: HttpBody): HttpRequestBodyStream? = when (body) {
    is HttpBody.Bytes -> HttpRequestBodyStream.fromByteArray(body.bytes())
    is HttpBody.Streaming -> if (body.isReplayable) {
        // FIXME: this is not particularly efficient since we have to launch a coroutine to fill it.
        // see https://github.com/awslabs/smithy-kotlin/issues/436
        ReadChannelBodyStream(body, coroutineContext)
    } else {
        // can only consume the stream once
        null
    }
    else -> null
}

/**
 * Convert an [HttpRequest] into a CRT HttpRequest for the purposes of signing
 */
@InternalApi
public suspend fun HttpRequest.toSignableCrtRequest(): HttpRequestCrt =
    HttpRequestCrt(
        method = method.name,
        encodedPath = url.encodedPath,
        headers = headers.toCrtHeaders(),
        body = signableBodyStream(body),
    )

// proxy the smithy-client-rt version of Headers to CRT (which is based on our client-rt version in the first place)
private class HttpHeadersCrt(val headers: HeadersBuilder) : HeadersCrt {
    override fun contains(name: String): Boolean = headers.contains(name)
    override fun entries(): Set<Map.Entry<String, List<String>>> = headers.entries()
    override fun getAll(name: String): List<String>? = headers.getAll(name)
    override fun isEmpty(): Boolean = headers.isEmpty()
    override fun names(): Set<String> = headers.names()
}

/**
 * Update a request builder from a CRT HTTP request (primary use is updating a request builder after signing)
 */
@InternalApi
public fun HttpRequestBuilder.update(crtRequest: HttpRequestCrt) {
    crtRequest.headers.entries().forEach { entry ->
        headers.appendMissing(entry.key, entry.value)
    }

    if (crtRequest.encodedPath.isNotBlank()) {
        crtRequest.queryParameters()?.let {
            it.forEach { key, values ->
                // the crt request has a url encoded path which means
                // simply appending missing could result in both the raw and percent-encoded
                // value being present. Instead just append new keys added by signing
                if (!url.parameters.contains(key)) {
                    url.parameters.appendAll(key, values)
                }
            }
        }
    }
}

/**
 * Get just the query parameters (if any)
 * @return the query parameters from the path or null if there weren't any
 */
@InternalApi
public fun HttpRequestCrt.queryParameters(): QueryParameters? {
    val idx = encodedPath.indexOf("?")
    if (idx < 0 || idx + 1 > encodedPath.length) return null

    val fragmentIdx = encodedPath.indexOf("#", startIndex = idx)
    val rawQueryString = if (fragmentIdx > 0) encodedPath.substring(idx + 1, fragmentIdx) else encodedPath.substring(idx + 1)
    return rawQueryString.splitAsQueryParameters()
}

/**
 * Get just the encoded path sans any query or fragment
 * @return the URI path segment from the encoded path
 */
@InternalApi
public fun HttpRequestCrt.path(): String {
    val idx = encodedPath.indexOf("?")
    return if (idx > 0) encodedPath.substring(0, idx) else encodedPath
}

// Convert CRT header type to SDK header type
@InternalApi
public fun aws.sdk.kotlin.crt.http.Headers.toSdkHeaders(): Headers {
    val headersBuilder = HeadersBuilder()

    forEach { key, values ->
        headersBuilder.appendAll(key, values)
    }

    return headersBuilder.build()
}

// Convert SDK header type to CRT header type
@InternalApi
public fun Headers.toCrtHeaders(): aws.sdk.kotlin.crt.http.Headers {
    val headersBuilder = aws.sdk.kotlin.crt.http.HeadersBuilder()

    forEach { key, values ->
        headersBuilder.appendAll(key, values)
    }

    return headersBuilder.build()
}
