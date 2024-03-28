/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.net.url

import aws.smithy.kotlin.runtime.net.*
import aws.smithy.kotlin.runtime.text.Scanner
import aws.smithy.kotlin.runtime.text.encoding.Encodable
import aws.smithy.kotlin.runtime.text.encoding.PercentEncoding
import aws.smithy.kotlin.runtime.text.ensurePrefix
import aws.smithy.kotlin.runtime.util.CanDeepCopy

/**
 * Represents a full, valid URL
 * @param scheme The wire protocol (e.g., http, https, etc.)
 * @param host The [Host] for the URL
 * @param port The remote port number for the URL (e.g., TCP port)
 * @param path The path element of this URL
 * @param parameters The query parameters for this URL.
 * @param fragment Optional fragment component of this URL (without the `#` character)
 * @param userInfo Optional user authentication information for this URL
 */
public class Url private constructor(
    public val scheme: Scheme,
    public val host: Host,
    public val port: Int,
    public val path: UrlPath,
    public val parameters: QueryParameters,
    public val userInfo: UserInfo,
    public val fragment: Encodable?,
) {
    public companion object {
        /**
         * Create a new [Url] via a DSL builder block
         * @param block The code to apply to the builder
         * @return A new [Url] instance
         */
        public inline operator fun invoke(block: Builder.() -> Unit): Url = Builder().apply(block).build()

        /**
         * Parse a URL string into a [Url] instance
         * @param value A URL string
         * @param encoding The components of the given [value] which are in a URL-encoded form. Defaults to
         * [UrlEncoding.All], meaning that the entire URL string is properly encoded.
         * @return A new [Url] instance
         */
        public fun parse(value: String, encoding: UrlEncoding = UrlEncoding.All): Url = try {
            Url {
                val scanner = Scanner(value)
                scanner.requireAndSkip("://") { scheme = Scheme.parse(it) }

                scanner.upToOrEnd("/", "?", "#") { authority ->
                    val innerScanner = Scanner(authority)

                    innerScanner.optionalAndSkip("@") {
                        userInfo.parseEncoded(it)
                    }

                    innerScanner.upToOrEnd { hostport ->
                        val (h, p) = hostport.parseHostPort()
                        host = h
                        p?.let { port = it }
                    }
                }

                scanner.ifStartsWith("/") {
                    scanner.upToOrEnd("?", "#") {
                        path.parse(it, encoding)
                    }
                }

                scanner.ifStartsWith("?") {
                    scanner.upToOrEnd("#") {
                        parameters.parse(it, encoding)
                    }
                }

                scanner.ifStartsWithSkip("#") {
                    scanner.upToOrEnd { parseFragment(it, encoding) }
                }
            }
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Cannot parse \"$value\" as a URL", e)
        }

        private fun stringify(
            scheme: Scheme,
            host: Host,
            port: Int,
            path: UrlPath,
            parameters: QueryParameters,
            userInfo: UserInfo,
            fragment: Encodable?,
        ): Pair<String, String> {
            var splitAt: Int

            val encoded = buildString {
                append(scheme.protocolName)
                append("://")
                append(userInfo)
                append(host.toUrlString())
                if (port != scheme.defaultPort) {
                    append(":")
                    append(port)
                }

                splitAt = length

                append(path)
                append(parameters)
                fragment?.let {
                    append('#')
                    append(it.encoded)
                }
            }

            val requestRelativePath = encoded
                .substring(splitAt)
                .ensurePrefix("/") // Request-line URI requires '/' for empty path

            return encoded to requestRelativePath
        }
    }

    private val encoded: String

    /**
     * Gets the host and port for the URL. The port is omitted if it's the default for the scheme.
     */
    public val hostAndPort: String = buildString {
        append(host)
        if (port != scheme.defaultPort) {
            append(':')
            append(port)
        }
    }

    /**
     * Gets a request-relative path string for this URL which is suitable for use in an HTTP request line. The given
     * path will include query parameters and the fragment and will be prepended with a `/` (even for empty paths
     * without a trailing slash configured). It will not include the protocol, host, port, or user info.
     *
     * For example:
     * ```
     * /path/to/resource?key=value#fragment
     * ```
     */
    public val requestRelativePath: String

    init {
        require(port in 1..65535) { "Given port $port is not in required range [1, 65535]" }

        stringify(scheme, host, port, path, parameters, userInfo, fragment).let {
            encoded = it.first
            requestRelativePath = it.second
        }
    }

    /**
     * Copy the properties of this [Url] instance into a new [Builder] object. Any changes to the builder *will not*
     * affect this instance.
     */
    public fun toBuilder(): Builder = Builder(this)

    /**
     * Returns a copy of this URL with the given DSL builder block applied to modify any desired fields. The returned
     * instance is disconnected from this instance.
     */
    public fun copy(block: Builder.() -> Unit = { }): Url = toBuilder().apply(block).build()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as Url

        if (scheme != other.scheme) return false
        if (host != other.host) return false
        if (port != other.port) return false
        if (path != other.path) return false
        if (parameters != other.parameters) return false
        if (userInfo != other.userInfo) return false
        if (fragment != other.fragment) return false

        return true
    }

    override fun hashCode(): Int {
        var result = scheme.hashCode()
        result = 31 * result + host.hashCode()
        result = 31 * result + port
        result = 31 * result + path.hashCode()
        result = 31 * result + parameters.hashCode()
        result = 31 * result + userInfo.hashCode()
        result = 31 * result + (fragment?.hashCode() ?: 0)
        return result
    }

    /**
     * Returns the encoded string representation of this URL
     */
    override fun toString(): String = encoded

    /**
     * A mutable builder used to construct [Url] instances
     */
    public class Builder internal constructor(url: Url?) : CanDeepCopy<Builder> {
        /**
         * Initialize an empty [Url] builder
         */
        public constructor() : this(null)

        // Simple fields

        /**
         * The wire protocol (e.g., http, https, etc.)
         */
        public var scheme: Scheme = url?.scheme ?: Scheme.HTTPS

        /**
         * The [Host] for the URL
         */
        public var host: Host = url?.host ?: Host.Domain("")

        /**
         * The remote port number for the URL (e.g., TCP port)
         */
        public var port: Int? = url?.port

        /**
         * Gets the host and port for the URL. The port is omitted if it's the default for the scheme.
         */
        public val hostAndPort: String
            get() = buildString {
                append(host)
                if (port != null && port != scheme.defaultPort) {
                    append(':')
                    append(port)
                }
            }

        // Path

        /**
         * Gets the URL path builder
         */
        public var path: UrlPath.Builder = url?.path?.toBuilder() ?: UrlPath.Builder()
            private set

        /**
         * Update the [UrlPath] of this URL via a DSL builder block
         * @param block The code to apply to the [UrlPath] builder
         */
        public inline fun path(block: UrlPath.Builder.() -> Unit) {
            path.apply(block)
        }

        // Query parameters

        /**
         * Gets the query parameters builder
         */
        public val parameters: QueryParameters.Builder = url?.parameters?.toBuilder() ?: QueryParameters.Builder()

        /**
         * Update the [QueryParameters] of this URL via a DSL builder block
         * @param block The code to apply to the [QueryParameters] builder
         */
        public inline fun parameters(block: QueryParameters.Builder.() -> Unit) {
            parameters.apply(block)
        }

        // User info

        /**
         * Get the user info builder
         */
        public val userInfo: UserInfo.Builder = url?.userInfo?.toBuilder() ?: UserInfo.Builder()

        /**
         * Set the user info in this URL via a DSL builder block
         * @param block The code to apply to the [UserInfo] builder
         */
        public inline fun userInfo(block: UserInfo.Builder.() -> Unit) {
            userInfo.apply(block)
        }

        // Fragment

        private var fragment: Encodable? = url?.fragment

        internal fun parseFragment(value: String, encoding: UrlEncoding) {
            if (UrlEncoding.Fragment in encoding) {
                encodedFragment = value
            } else {
                decodedFragment = value
            }
        }

        /**
         * Get or set the fragment as a **decoded** string
         */
        public var decodedFragment: String?
            get() = fragment?.decoded
            set(value) { fragment = value?.let(PercentEncoding.Fragment::encodableFromDecoded) }

        /**
         * Get or set the fragment as an **encoded** string
         */
        public var encodedFragment: String?
            get() = fragment?.encoded
            set(value) { fragment = value?.let(PercentEncoding.Fragment::encodableFromEncoded) }

        // Build method

        /**
         * Build a new [Url] from the currently-configured builder values
         * @return A new [Url] instance
         */
        public fun build(): Url = Url(
            scheme,
            host,
            port ?: scheme.defaultPort,
            path.build(),
            parameters.build(),
            userInfo.build(),
            fragment,
        )

        override fun deepCopy(): Builder = Builder().also {
            it.scheme = scheme
            it.host = host
            it.port = port
            it.path.copyFrom(path)
            it.parameters.copyFrom(parameters)
            it.userInfo.copyFrom(userInfo)
            it.fragment = fragment
        }

        /**
         * Copies state from [url] into this builder. All existing state is overwritten.
         */
        public fun copyFrom(url: Url) {
            scheme = url.scheme
            host = url.host
            port = url.port
            path.copyFrom(url.path)
            parameters.copyFrom(url.parameters)
            userInfo.copyFrom(url.userInfo)
            fragment = url.fragment
        }

        /**
         * Gets a request-relative path string for this URL which is suitable for use in an HTTP request line. The given
         * path will include query parameters and the fragment and will be prepended with a `/` (even for empty paths
         * without a trailing slash configured). It will not include the protocol, host, port, or user info.
         *
         * For example:
         * ```
         * /path/to/resource?key=value#fragment
         * ```
         */
        public val requestRelativePath: String
            get() = buildString {
                append(path.encoded)
                append(parameters.encoded)
                fragment?.let {
                    append('#')
                    append(it.encoded)
                }
            }.ensurePrefix("/")
    }
}

private fun String.parseHostPort(): Pair<Host, Int?> =
    if (startsWith('[')) {
        val bracketEnd = indexOf(']')
        require(bracketEnd > 0) { "unmatched [ or ]" }

        val encodedHostName = substring(1, bracketEnd)
        val decodedHostName = PercentEncoding.Host.decode(encodedHostName)
        val host = Host.parse(decodedHostName)
        require(host is Host.IpAddress && host.address is IpV6Addr) { "non-ipv6 host was enclosed in []-brackets" }

        val port = when (getOrNull(bracketEnd + 1)) {
            ':' -> substring(bracketEnd + 2).toInt()
            null -> null
            else -> throw IllegalArgumentException("unexpected characters after ]")
        }

        host to port
    } else {
        val parts = split(':')

        val decodedHostName = PercentEncoding.Host.decode(parts[0])
        val host = Host.parse(decodedHostName)
        require(host !is Host.IpAddress || host.address !is IpV6Addr) { "ipv6 host given without []-brackets" }

        val port = parts.getOrNull(1)?.toInt()
        host to port
    }
