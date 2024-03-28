/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.net.url

import aws.smithy.kotlin.runtime.collections.views.asView
import aws.smithy.kotlin.runtime.text.encoding.Encodable
import aws.smithy.kotlin.runtime.text.encoding.PercentEncoding

/**
 * Represents the path component of a URL
 * @param segments A list of path segments
 * @param trailingSlash Indicates whether a trailing slash is present in the path (e.g., "/foo/bar/" vs "/foo/bar")
 */
public class UrlPath private constructor(
    public val segments: List<Encodable>,
    public val trailingSlash: Boolean = false,
) {
    public companion object {
        /**
         * No URL path
         */
        public val Empty: UrlPath = UrlPath { }

        /**
         * Create a new [UrlPath] via a DSL builder block
         * @param block The code to apply to the builder
         * @return A new [UrlPath] instance
         */
        public inline operator fun invoke(block: Builder.() -> Unit): UrlPath = Builder().apply(block).build()

        private fun asDecoded(segments: List<Encodable>, trailingSlash: Boolean) =
            asString(segments, trailingSlash, Encodable::decoded)

        private fun asEncoded(segments: List<Encodable>, trailingSlash: Boolean) =
            asString(segments, trailingSlash, Encodable::encoded)

        private fun asString(segments: List<Encodable>, trailingSlash: Boolean, encodableForm: (Encodable) -> String) =
            segments.joinToString(
                separator = "/",
                prefix = if (segments.isEmpty()) "" else "/",
                postfix = if (trailingSlash) "/" else "",
                transform = encodableForm,
            )

        /**
         * Parse a **decoded** path string into a [UrlPath] instance
         * @param decoded A decoded path string
         * @return A new [UrlPath] instance
         */
        public fun parseDecoded(decoded: String): UrlPath = UrlPath { parseDecoded(decoded) }

        /**
         * Parse an **encoded** path string into a [UrlPath] instance
         * @param encoded An encoded path string
         * @return A new [UrlPath] instance
         */
        public fun parseEncoded(encoded: String): UrlPath = UrlPath { parseEncoded(encoded) }
    }

    /**
     * Copy the properties of this [UrlPath] instance into a new [Builder] object. Any changes to the builder *will not*
     * affect this instance.
     */
    public fun toBuilder(): Builder = Builder(this)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as UrlPath

        if (segments != other.segments) return false
        if (trailingSlash != other.trailingSlash) return false

        return true
    }

    override fun hashCode(): Int {
        var result = segments.hashCode()
        result = 31 * result + trailingSlash.hashCode()
        return result
    }

    public val decoded: String
        get() = asDecoded(segments, trailingSlash)

    public val encoded: String
        get() = asEncoded(segments, trailingSlash)

    override fun toString(): String = encoded

    /**
     * A mutable builder used to construct [UrlPath] instances
     */
    public class Builder internal constructor(path: UrlPath?) {
        /**
         * Initialize an empty [UrlPath] builder
         */
        public constructor() : this(null)

        /**
         * Get or set the URL path as a **decoded** string.
         */
        public var decoded: String
            get() = asDecoded(segments, trailingSlash)
            set(value) { parseDecoded(value) }

        /**
         * Get or set the URL path as an **encoded** string.
         */
        public var encoded: String
            get() = asEncoded(segments, trailingSlash)
            set(value) { parseEncoded(value) }

        /**
         * Gets the segments of this URL path
         */
        public val segments: MutableList<Encodable> = path?.segments?.toMutableList() ?: mutableListOf()

        /**
         * A mutable list of **decoded** path segments. Any changes to this list will update the builder.
         */
        public val decodedSegments: MutableList<String> = segments.asView(
            Encodable::decoded,
            PercentEncoding.Path::encodableFromDecoded,
        )

        /**
         * Applies the given DSL block to the **decoded** path segments. Any changes to the list will update the
         * builder.
         */
        public fun decodedSegments(block: MutableList<String>.() -> Unit) {
            decodedSegments.apply(block)
        }

        /**
         * A mutable list of **encoded** path segments. Any changes to this list will update the builder.
         */
        public val encodedSegments: MutableList<String> = segments.asView(
            Encodable::encoded,
            PercentEncoding.Path::encodableFromEncoded,
        )

        /**
         * Applies the given DSL block to the **encoded** path segments. Any changes to the list will update the
         * builder.
         */
        public fun encodedSegments(block: MutableList<String>.() -> Unit) {
            encodedSegments.apply(block)
        }

        /**
         * Normalizes the segments of a URL path according to the following rules:
         * * The returned path always begins with `/` (e.g., `a/b/c` → `/a/b/c`)
         * * The returned path ends with `/` if the input path also does
         * * Empty segments are discarded (e.g., `/a//b` → `/a/b`)
         * * Segments of `.` are discarded (e.g., `/a/./b` → `/a/b`)
         * * Segments of `..` are used to discard ancestor paths (e.g., `/a/b/../c` → `/a/c`)
         * * All other segments are unmodified
         */
        public fun normalize() {
            segments.listIterator().apply {
                while (hasNext()) {
                    when (next().decoded) {
                        ".", "" -> remove()
                        ".." -> {
                            remove()
                            check(hasPrevious()) { "Cannot normalize because \"..\" has no parent" }
                            previous()
                            remove()
                        }
                    }
                }
            }

            if (segments.isEmpty()) trailingSlash = true
        }

        /**
         * Indicates whether a trailing slash is present in the path (e.g., "/foo/bar/" vs "/foo/bar")
         */
        public var trailingSlash: Boolean = path?.trailingSlash ?: false

        internal fun parse(text: String, encoding: UrlEncoding): Unit =
            if (UrlEncoding.Path in encoding) parseEncoded(text) else parseDecoded(text)

        internal fun parseDecoded(decoded: String): Unit = parse(decoded, PercentEncoding.Path::encodableFromDecoded)
        internal fun parseEncoded(encoded: String): Unit = parse(encoded, PercentEncoding.Path::encodableFromEncoded)

        private fun parse(text: String, toEncodable: (String) -> Encodable) {
            segments.clear()

            when (text) {
                "" -> trailingSlash = false
                "/" -> trailingSlash = true
                else -> {
                    val noLeadingSlash = text.removePrefix("/")
                    trailingSlash = noLeadingSlash.endsWith('/')

                    val trimmed = if (trailingSlash) noLeadingSlash.removeSuffix("/") else noLeadingSlash
                    trimmed.split('/').mapTo(segments, toEncodable)
                }
            }
        }

        /**
         * Build a new [UrlPath] from the currently-configured builder values
         * @return A new [UrlPath] instance
         */
        public fun build(): UrlPath = UrlPath(segments.toList(), trailingSlash)

        /**
         * Copies the state from [other] into this builder. All existing state is overwritten.
         */
        public fun copyFrom(other: UrlPath) {
            segments.clear()
            segments.addAll(other.segments)
            trailingSlash = other.trailingSlash
        }

        /**
         * Copies the state from [other] into this builder. All existing state is overwritten.
         */
        public fun copyFrom(other: Builder) {
            segments.clear()
            segments.addAll(other.segments)
            trailingSlash = other.trailingSlash
        }
    }
}
