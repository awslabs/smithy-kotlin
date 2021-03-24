/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.aws.clientrt.serde.xml

/**
 * Raw tokens produced when reading a XML document as a stream
 */
sealed class XmlToken {
    abstract val depth: Int
    /**
     * An namespace declaration (xmlns)
     */
    data class Namespace(val uri: String, val prefix: String? = null)

    /**
     * Defines the name and namespace of an element
     * @property local The localized name of an element
     * @property ns The namespace this element belongs to
     */
    data class QualifiedName(val local: String, val ns: Namespace? = null) {
        constructor(local: String, uri: String?, prefix: String?) : this(local, if (uri != null) Namespace(uri, prefix) else null)
        constructor(local: String, uri: String?) : this(local, uri, null)

        override fun toString(): String {
            return if (ns == null) local else "$ns:$local"
        }
    }

    /**
     * The opening of an XML element
     */
    data class BeginElement(
        override val depth: Int,
        val name: QualifiedName,
        val attributes: Map<QualifiedName, String> = emptyMap(),
        val nsDeclarations: List<Namespace> = emptyList()
    ) : XmlToken() {
        // Convenience constructor for name-only nodes.
        constructor(depth: Int, name: String) : this(depth, QualifiedName(name))
        // Convenience constructor for name-only nodes with attributes.
        constructor(depth: Int, name: String, attributes: Map<QualifiedName, String>) : this(depth, QualifiedName(name), attributes)

        override fun toString(): String = "<${this.name} (${this.depth})>"
    }

    /**
     * The closing of an XML element
     */
    data class EndElement(override val depth: Int, val name: QualifiedName) : XmlToken() {
        // Convenience constructor for name-only nodes.
        constructor(depth: Int, name: String) : this(depth, QualifiedName(name))

        override fun toString(): String = "</${this.name}> (${this.depth})"
    }

    /**
     * An XML element text as string
     */
    data class Text(override val depth: Int, val value: String?) : XmlToken() {
        override fun toString(): String = "${this.value} (${this.depth})"
    }

    object StartDocument : XmlToken() {
        override val depth: Int
            get() = 0
    }

    /**
     * The end of the XML stream to signal that the XML-encoded value has no more
     * tokens
     */
    object EndDocument : XmlToken() {
        override val depth: Int
            get() = 0
    }

    override fun toString(): String = when (this) {
        is BeginElement -> "<${this.name}>"
        is EndElement -> "</${this.name}>"
        is Text -> "${this.value}"
        StartDocument -> "[StartDocument]"
        EndDocument -> "[EndDocument]"
    }
}

// Return true if the passed in node is the beginning node, false otherwise.
fun XmlToken?.terminates(beginToken: XmlToken?): Boolean {
    if (this == null || beginToken == null) return false
    if (this !is XmlToken.EndElement) return false
    if (beginToken !is XmlToken.BeginElement) return false
    if (depth != beginToken.depth) return false
    if (name != beginToken.name) return false

    return true
}