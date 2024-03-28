/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.http.request

import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.http.*
import aws.smithy.kotlin.runtime.io.*
import aws.smithy.kotlin.runtime.net.url.Url
import aws.smithy.kotlin.runtime.util.CanDeepCopy

/**
 * Used to construct an HTTP request
 * @param method The HTTP method (verb) to use when making the request
 * @param url Endpoint to make request to
 * @param headers HTTP headers
 * @param body Outgoing payload. Initially empty
 */
public class HttpRequestBuilder private constructor(
    public var method: HttpMethod,
    public val url: Url.Builder,
    public val headers: HeadersBuilder,
    public var body: HttpBody,
    public val trailingHeaders: DeferredHeadersBuilder,
) : CanDeepCopy<HttpRequestBuilder> {
    public constructor() : this(HttpMethod.GET, Url.Builder(), HeadersBuilder(), HttpBody.Empty, DeferredHeadersBuilder())

    public fun build(): HttpRequest =
        HttpRequest(method, url.build(), if (headers.isEmpty()) Headers.Empty else headers.build(), body, if (trailingHeaders.isEmpty()) DeferredHeaders.Empty else trailingHeaders.build())

    override fun deepCopy(): HttpRequestBuilder =
        HttpRequestBuilder(method, url.deepCopy(), headers.deepCopy(), body, trailingHeaders.deepCopy())

    override fun toString(): String = buildString {
        append("HttpRequestBuilder(method=$method, url=$url, headers=$headers, body=$body, trailingHeaders=$trailingHeaders)")
    }
}

internal data class HttpRequestBuilderView(
    internal val builder: HttpRequestBuilder,
    internal val allowToBuilder: Boolean,
) : HttpRequest {
    override val method: HttpMethod = builder.method
    override val url: Url by lazy { builder.url.build() }
    override val headers: Headers by lazy { builder.headers.build() }
    override val body: HttpBody = builder.body
    override val trailingHeaders: DeferredHeaders by lazy { builder.trailingHeaders.build() }
}

/**
 * Create a read-only view of a builder. Often, we need a read-only view of a builder that _may_ get modified.
 * This would normally require a round trip invoking [HttpRequestBuilder.build] and then converting that back
 * to a builder using [HttpRequest.toBuilder]. Instead, we can create an immutable view of a builder that
 * is cheap to convert to a builder.
 *
 * @param allowToBuilder flag controlling how this type will behave when [HttpRequest.toBuilder] is invoked. When
 * false an exception will be thrown, otherwise it will succeed.
 */
@InternalApi
public fun HttpRequestBuilder.immutableView(
    allowToBuilder: Boolean = false,
): HttpRequest = HttpRequestBuilderView(this, allowToBuilder)

// convenience extensions

/**
 * Modify the URL inside the block
 */
public inline fun HttpRequestBuilder.url(block: Url.Builder.() -> Unit) {
    url.apply(block)
}

/**
 * Set values from an existing [Url] instance
 */
public fun HttpRequestBuilder.url(value: Url) {
    url.copyFrom(value)
}

/**
 * Modify the headers inside the given block
 */
public inline fun HttpRequestBuilder.headers(block: HeadersBuilder.() -> Unit) {
    headers.apply(block)
}

/**
 * Add a single header. This will append to any existing headers with the same name.
 */
public fun HttpRequestBuilder.header(name: String, value: String): Unit = headers.append(name, value)

/**
 * Dump a debug description of the request
 *
 * @param dumpBody Flag controlling whether to also dump the body out. If true the body will be consumed and
 * replaced.
 */
@InternalApi
public suspend fun dumpRequest(request: HttpRequestBuilder, dumpBody: Boolean): String {
    val buffer = SdkBuffer()

    // TODO - we have no way to know the http version at this level to set HTTP/x.x
    buffer.writeUtf8("${request.method} ${request.url.requestRelativePath}\r\n")
    buffer.writeUtf8("Host: ${request.url.host}\r\n")

    val contentLength = request.headers["Content-Length"]?.toLongOrNull() ?: (request.body.contentLength ?: 0)
    if (contentLength > 0) {
        buffer.writeUtf8("Content-Length: $contentLength\r\n")
    }

    val skip = setOf("Host", "Content-Length")
    request.headers.entries()
        .filterNot { it.key in skip }
        .forEach { (key, values) ->
            buffer.writeUtf8(values.joinToString(separator = ";", prefix = "$key: ", postfix = "\r\n"))
        }

    buffer.writeUtf8("\r\n")
    if (dumpBody) {
        when (val body = request.body) {
            is HttpBody.Bytes -> buffer.write(body.bytes())
            is HttpBody.ChannelContent, is HttpBody.SourceContent -> {
                // consume the stream and replace the body
                request.body = copyHttpBody(request.body, buffer)
            }
            is HttpBody.Empty -> { } // nothing to dump
        }
    }

    return buffer.readUtf8()
}

private suspend fun copyHttpBody(original: HttpBody, buffer: SdkBuffer): HttpBody {
    val content = original.readAll() ?: return HttpBody.Empty
    buffer.write(content)
    return object : HttpBody.SourceContent() {
        override fun readFrom(): SdkSource =
            content.source()

        // even though we know the content length we preserve the original in case it was chunked encoding
        override val contentLength: Long? = original.contentLength
        override val isOneShot: Boolean = original.isOneShot
        override val isDuplex: Boolean = original.isDuplex
    }
}
