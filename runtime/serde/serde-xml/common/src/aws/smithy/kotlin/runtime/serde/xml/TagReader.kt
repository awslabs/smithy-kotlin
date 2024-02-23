/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.serde.xml

import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.io.Closeable
import aws.smithy.kotlin.runtime.serde.DeserializationException

/**
 * An [XmlStreamReader] scoped to reading a single XML element [startTag]
 * [TagReader] provides a "tag" scoped view into an XML document. Methods return
 * `null` when the current tag has been exhausted.
 */
@InternalApi
public class TagReader(
    public val startTag: XmlToken.BeginElement,
    private val reader: XmlStreamReader,
) : Closeable {
    private var last: TagReader? = null
    private var closed = false

    public fun nextToken(): XmlToken? {
        if (closed) return null
        val peek = reader.peek()
        if (peek.terminates(startTag)) {
            // consume it and close the tag reader
            reader.nextToken()
            closed = true
            return null
        }
        return reader.nextToken()
    }

    public fun nextHasValue(): Boolean {
        if (closed) return false
        return reader.peek() !is XmlToken.EndElement
    }

    public fun skipNext() {
        if (closed) return
        reader.skipNext()
    }

    public fun skipCurrent() {
        if (closed) return
        reader.skipCurrent()
    }

    override fun close(): Unit = drop()

    public fun drop() {
        do {
            val tok = nextToken()
        } while (tok != null)
        // // consume the end token for this element
        // // FIXME - consuming the next token that ends this messes up the subtree reader state, `nextToken()` will now start
        // // to return more tokens
        // val next = parent.peek()
        // if (next.terminates(startElement)) {
        //     parent.nextToken()
        // }
    }

    public fun nextTag(): TagReader? {
        last?.drop()

        var cand = nextToken()
        while (cand != null && cand !is XmlToken.BeginElement) {
            cand = nextToken()
        }

        val nextTok = cand as? XmlToken.BeginElement

        return nextTok?.tagReader(reader).also { newScope ->
            last = newScope
        }
    }
}

@InternalApi
public fun XmlStreamReader.root(): TagReader {
    val start = seek<XmlToken.BeginElement>() ?: error("expected start tag: last = $lastToken")
    return start.tagReader(this)
}

/**
 * Create a new reader scoped to this element.
 */
@InternalApi
public fun XmlToken.BeginElement.tagReader(reader: XmlStreamReader): TagReader {
    val start = reader.lastToken as? XmlToken.BeginElement ?: error("expected start tag found ${reader.lastToken}")
    check(name == start.name) { "expected start tag $name but current reader state is on ${start.name}" }
    return TagReader(this, reader)
}

/**
 * Consume the next token and map the data value from it using [transform]
 *
 * If the next token is not [XmlToken.Text] an exception will be thrown
 */
@InternalApi
public inline fun <T> TagReader.mapData(transform: (String) -> T): T =
    transform(data())

@InternalApi
public fun TagReader.data(): String =
    when (val next = nextToken()) {
        is XmlToken.Text -> next.value ?: ""
        null, is XmlToken.EndElement -> ""
        else -> throw DeserializationException("expected XmlToken.Text element, found $next")
    }

@InternalApi
public fun TagReader.tryData(): Result<String> = runCatching { data() }

//
// private fun <T> TagReader.mapOrThrow(expected: String, mapper: (String) -> T?): T =
//     map { raw ->
//         mapper(raw) ?: throw DeserializationException("could not deserialize $raw as $expected for tag ${this.startTag}")
//     }
//
// @InternalApi
// public fun TagReader.readInt(): Int = mapOrThrow("Int", String::toIntOrNull)
//
// @InternalApi
// public fun TagReader.readShort(): Short = mapOrThrow("Short", String::toShortOrNull)
//
// @InternalApi
// public fun TagReader.readLong(): Long = mapOrThrow("Long", String::toLongOrNull)
//
// @InternalApi
// public fun TagReader.readFloat(): Float = mapOrThrow("Float", String::toFloatOrNull)
//
// @InternalApi
// public fun TagReader.readDouble(): Double = mapOrThrow("Double", String::toDoubleOrNull)
//
// @InternalApi
// public fun TagReader.readByte(): Byte = mapOrThrow("Byte") { it.toIntOrNull()?.toByte() }
//
// @InternalApi
// public fun TagReader.readBoolean(): Boolean = mapOrThrow("Boolean", String::toBoolean)