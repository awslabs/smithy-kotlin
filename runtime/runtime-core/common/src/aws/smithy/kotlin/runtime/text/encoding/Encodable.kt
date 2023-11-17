/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.text.encoding

public class Encodable internal constructor(
    public val decoded: String,
    public val encoded: String,
    public val encoding: Encoding,
) {
    public companion object {
        public val Empty: Encodable = Encodable("", "", Encoding.None)
    }

    public val isEmpty: Boolean = decoded.isEmpty() && encoded.isEmpty()
    public val isNotEmpty: Boolean = !isEmpty

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Encodable) return false

        if (decoded != other.decoded) return false
        if (encoded != other.encoded) return false
        return encoding == other.encoding
    }

    override fun hashCode(): Int {
        var result = decoded.hashCode()
        result = 31 * result + encoded.hashCode()
        result = 31 * result + encoding.hashCode()
        return result
    }

    public fun reencode(): Encodable = encoding.encodableFromDecoded(decoded)

    override fun toString(): String = buildString {
        append("Encodable(decoded=")
        append(decoded)
        append(", encoded=")
        append(encoded)
        append(", encoding=")
        append(encoding.name)
        append(")")
    }
}
