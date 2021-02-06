/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.aws.clientrt.serde.xml

import software.aws.clientrt.serde.*

/**
 * A deserializer for XML encoded data.
 *
 * This class implements interfaces that support a variety of serialization formats.  There
 * is not an explicit end-tag consumption operation provided by these interfaces.  This implementation
 * consumes end tokens by checking for them each time a new token is to be consumed.  If the next
 * token in this case is an end token, it is consumed and discarded.
 *
 * TODO: To verify correctness, a stack of open node names is maintained to match expected against actual node traversal.
 * TODO: This is not necessary for parsing and should be removed before GA.
 */
class XmlDeserializer(
    private val reader: XmlStreamReader,
    private val nodeNameStack: MutableList<XmlToken.QualifiedName> = mutableListOf()
) : Deserializer {
    constructor(input: ByteArray) : this(xmlStreamReader(input))

    /**
     * Deserialize an element with children of arbitrary values.
     *
     * @param descriptor A SdkFieldDescriptor which defines the name of the node wrapping the struct values.
     */
    override fun deserializeStruct(descriptor: SdkObjectDescriptor): Deserializer.FieldIterator {
        reader.takeIfToken<XmlToken.EndElement>(nodeNameStack)

        val beginNode = reader.takeToken<XmlToken.BeginElement>(nodeNameStack) // Consume the container start tag

        return XmlFieldIterator(this, reader.currentDepth, reader, beginNode, descriptor, nodeNameStack)
    }

    /**
     * Deserialize an element with identically-typed children.
     */
    override fun deserializeList(descriptor: SdkFieldDescriptor): Deserializer.ElementIterator {
        reader.takeIfToken<XmlToken.EndElement>(nodeNameStack)

        val beginNode = reader.takeToken<XmlToken.BeginElement>(nodeNameStack) // Consume the container start tag
        require(descriptor.serialName.toQualifiedName() == beginNode.qualifiedName) { "Expected list start tag of ${beginNode.qualifiedName} but got ${descriptor.serialName}" }

        return CompositeIterator(this, reader.currentDepth, reader, beginNode, descriptor, nodeNameStack)
    }

    /**
     * Deserialize an element with identically-typed children of key/value pairs.
     */
    override fun deserializeMap(descriptor: SdkFieldDescriptor): Deserializer.EntryIterator {
        reader.takeIfToken<XmlToken.EndElement>(nodeNameStack)

        val beginNode = reader.takeToken<XmlToken.BeginElement>(nodeNameStack) // Consume the container start tag
        require(descriptor.serialName.toQualifiedName() == beginNode.qualifiedName) { "Expected map start tag of ${beginNode.qualifiedName} but got ${descriptor.serialName}" }

        return CompositeIterator(this, reader.currentDepth, reader, beginNode, descriptor, nodeNameStack)
    }
}

/**
 * Captures state and implements interfaces necessary to deserialize Lists and Maps.
 */
private class CompositeIterator(
    private val deserializer: Deserializer,
    private val depth: Int,
    private val reader: XmlStreamReader,
    private val beginNode: XmlToken.BeginElement,
    private val descriptor: SdkFieldDescriptor,
    private val nodeNameStack: MutableList<XmlToken.QualifiedName>
) : Deserializer.EntryIterator, Deserializer.ElementIterator {

    private var consumedWrapper = false // Signals if outermost tag initially passed to CompositeIterator has been taken

    // Deserializer.EntryIterator, Deserializer.ElementIterator
    override fun hasNextElement(): Boolean {
        require(reader.currentDepth >= depth) { "Unexpectedly traversed beyond $beginNode with depth ${reader.currentDepth}" }

        val flattenedList = descriptor.findTrait<XmlList>()?.flattened == true

        if (!consumedWrapper && !flattenedList) {
            val nextToken = reader.peekNextToken()

            if (nextToken is XmlToken.EndElement) return false // empty list
            if (nextToken is XmlToken.BeginElement) {
                require(nextToken.qualifiedName.name == descriptor.getWrapperName()) { "Expected entry wrapper ${descriptor.serialName} but got ${nextToken.qualifiedName}" }
                consumedWrapper = true
            } else {
                error("Unexpected next token when parsing list: $nextToken")
            }
        }

        return when (val nextToken = reader.peekNextToken()) {
            XmlToken.EndDocument -> false
            is XmlToken.EndElement ->
                if (reader.currentDepth < depth) {
                    false
                } else if (reader.currentDepth == depth && nextToken.qualifiedName == beginNode.qualifiedName && !flattenedList) {
                    false
                } else {
                    reader.takeToken<XmlToken.EndElement>(nodeNameStack) // Consume terminating node of child
                    hasNextElement() // recurse
                }
            else -> true
        }
    }

    override fun hasNextEntry(): Boolean {
        require(reader.currentDepth >= depth) { "Unexpectedly traversed beyond $beginNode with depth ${reader.currentDepth}" }

        reader.takeIfToken<XmlToken.BeginElement>(nodeNameStack) { beginToken ->
            val mapInfo = descriptor.expectTrait<XmlMap>()
            if (!consumedWrapper && !mapInfo.flattened) {
                consumedWrapper = true
                require(beginToken.qualifiedName.name == mapInfo.entry) { "Expected node ${mapInfo.entry} but got ${beginToken.qualifiedName}" }
                //reader.peekToken<XmlToken.BeginElement>()
            }
        }

        return when (val nextToken = reader.peekNextToken()) {
            XmlToken.EndDocument -> false
            is XmlToken.EndElement ->
                if (reader.currentDepth == depth && nextToken.qualifiedName == beginNode.qualifiedName) {
                    false
                } else {
                    reader.takeToken<XmlToken.EndElement>(nodeNameStack) // Consume terminating node of child
                    hasNextEntry() // recurse
                }
            else -> true
        }
    }

    // Deserializer.EntryIterator
    override fun key(): String {
        val mapInfo = descriptor.expectTrait<XmlMap>()
        reader.takeIfToken<XmlToken.EndElement>(nodeNameStack)

        val keyStartToken = reader.takeToken<XmlToken.BeginElement>(nodeNameStack)
        require(keyStartToken.qualifiedName.name == mapInfo.keyName) { "Expected key name to be ${mapInfo.keyName} but got ${keyStartToken.qualifiedName}" }
        val key = reader.takeToken<XmlToken.Text>(nodeNameStack).value
            ?: error("Expected string value for key, but found null.")
        val keyEndToken = reader.takeToken<XmlToken.EndElement>(nodeNameStack)
        require(keyEndToken.qualifiedName.name == mapInfo.keyName) { "Expected key name to be ${mapInfo.keyName} but got ${keyStartToken.qualifiedName}" }

        val valueToken = reader.takeToken<XmlToken.BeginElement>(nodeNameStack)

        require(valueToken.qualifiedName.name == mapInfo.valueName)

        return key
    }

    override fun deserializeByte(): Byte {
        reader.takeIfToken<XmlToken.EndElement>(nodeNameStack)
        reader.consumeListWrapper(descriptor, nodeNameStack)

        TODO() // return deserializer.deserializeByte()
    }

    override fun deserializeInt(): Int {
        reader.takeIfToken<XmlToken.EndElement>(nodeNameStack)
        reader.consumeListWrapper(descriptor, nodeNameStack)

        TODO() // return deserializer.deserializeInt()
    }

    override fun deserializeShort(): Short {
        reader.takeIfToken<XmlToken.EndElement>(nodeNameStack)
        reader.consumeListWrapper(descriptor, nodeNameStack)
        TODO() // return deserializer.deserializeShort()
    }

    override fun deserializeLong(): Long {
        reader.takeIfToken<XmlToken.EndElement>(nodeNameStack)
        reader.consumeListWrapper(descriptor, nodeNameStack)
        TODO() // return deserializer.deserializeLong()
    }

    override fun deserializeFloat(): Float {
        reader.takeIfToken<XmlToken.EndElement>(nodeNameStack)
        reader.consumeListWrapper(descriptor, nodeNameStack)
        TODO() // return deserializer.deserializeFloat()
    }

    override fun deserializeDouble(): Double {
        reader.takeIfToken<XmlToken.EndElement>(nodeNameStack)
        reader.consumeListWrapper(descriptor, nodeNameStack)
        TODO() // return deserializer.deserializeDouble()
    }

    override fun deserializeString(): String {
        reader.takeIfToken<XmlToken.EndElement>(nodeNameStack)
        reader.consumeListWrapper(descriptor, nodeNameStack)
        TODO() // return deserializer.deserializeString()
    }

    override fun deserializeBoolean(): Boolean {
        reader.takeIfToken<XmlToken.EndElement>(nodeNameStack)
        reader.consumeListWrapper(descriptor, nodeNameStack)
        TODO() // return deserializer.deserializeBoolean()
    }

    override fun deserializeNull(): Nothing? {
        check(reader.takeIfToken<XmlToken.EndElement>(nodeNameStack)) { "Expected XmlToken.EndElement" }
        return null
    }

    override fun nextHasValue(): Boolean {
        val nextToken = reader.peekNextToken()

        return nextToken is XmlToken.Text || nextToken is XmlToken.BeginElement
    }
}

data class AttributeParseState(val trait: XmlAttribute, val token: XmlToken.BeginElement, val lastFromNode: Boolean)

private class XmlFieldIterator(
    private val deserializer: Deserializer,
    private val depth: Int,
    private val reader: XmlStreamReader,
    private val beginNode: XmlToken.BeginElement,
    private val descriptor: SdkObjectDescriptor,
    private val nodeNameStack: MutableList<XmlToken.QualifiedName>
) : Deserializer.FieldIterator, Deserializer {
    // This stores transient state that must exist between the time of findNextFieldIndex() and parsing of a primitive value.
    // This state is necessary such that when the value is read, we know to take either from TEXT or a named attribute.
    // Each time the value is read, it is explicitly set to null, and null state is guarded to help ensure that stale
    // values are not acted upon.
    private var attributeParseState: AttributeParseState? = null
    private val handledFields = mutableListOf<SdkFieldDescriptor>()

    // Deserializer.FieldIterator
    override fun findNextFieldIndex(): Int? {
        check(attributeParseState == null) { "Expected nextFieldValueSource to be null but found $attributeParseState" }
        require(reader.currentDepth >= depth) { "Unexpectedly traversed beyond $beginNode with depth ${reader.currentDepth}" }

        return when (val nextToken = reader.peekNextToken()) {
            XmlToken.EndDocument -> null
            is XmlToken.EndElement -> {
                // consume the token
                val endToken = reader.takeToken<XmlToken.EndElement>(nodeNameStack)

                if (reader.currentDepth == depth && endToken.qualifiedName == beginNode.qualifiedName) {
                    null
                } else {
                    findNextFieldIndex() // recurse
                }
            }
            is XmlToken.BeginElement -> {
                val field = findFieldIndex(descriptor.fields, nextToken)

                if (!isContainerType(field)) {
                    //val token = reader.peekToken<XmlToken.BeginElement>()
                    val xmlAttribTrait = field?.findTrait<XmlAttribute>()
                    attributeParseState = when (field?.findTrait<XmlAttribute>()) {
                        null -> {
                            // All attributes will be consumed first. Since we did not retrieve an attribute we
                            // can consume this node as this will be the TEXT element.
                            reader.takeToken<XmlToken.BeginElement>(nodeNameStack)

                            if (reader.peekNextToken() is XmlToken.EndElement) return findNextFieldIndex()
                            check(reader.peekNextToken() is XmlToken.Text) { "Expected to read a TEXT token to retrieve value but got ${reader.peekNextToken()}" }
                            null
                        }
                        else -> null //AttributeParseState(xmlAttribTrait!!, token, lastAttributeInNode(handledFields, descriptor.fields, field))
                    }
                }

                field?.index ?: Deserializer.FieldIterator.UNKNOWN_FIELD
            }
            else -> throw DeserializationException("Unexpected token $nextToken")
        }
    }

    // Determine if all attributes of the currently parsing node have already been seen
    private fun lastAttributeInNode(handledFieldList: List<SdkFieldDescriptor>, fields: List<SdkFieldDescriptor>, targetDescriptor: SdkFieldDescriptor): Boolean {
        val allVisitedAttribsForTargetDescriptor = handledFieldList.filter { descriptor -> descriptor.serialName == targetDescriptor.serialName }
        val allAttribsOfTargetDescriptor = fields.filter { descriptor -> descriptor.serialName == targetDescriptor.serialName }

        return allVisitedAttribsForTargetDescriptor == allAttribsOfTargetDescriptor
    }

    // Return the next field to parse. First looking for attributes and then once all attribues are consumed, taking the TEXT
    // This function mutates class-level state regarding what attributes have already been seen.
    private fun findFieldIndex(fields: List<SdkFieldDescriptor>, nextToken: XmlToken.BeginElement): SdkFieldDescriptor? {
        // Find attributes we have not already consumed
        val unhandledAttribFields = fields
            .filter { field -> !handledFields.contains(field) }
            .filter { field -> field.findTrait<XmlAttribute>() != null }
            // FIXME: The following filter needs to take XML namespace into account when matching.
            .filter { field -> field.serialName.toQualifiedName() == nextToken.qualifiedName }

        // If present, take the next and return
        if (unhandledAttribFields.isNotEmpty()) {
            val nextAttribField = unhandledAttribFields.first()

            handledFields.add(nextAttribField)

            return nextAttribField
        }

        val flattenedListField = fields.find { field -> field.findTrait<XmlList>()?.flattened ?: false }
        if (flattenedListField != null && flattenedListField.expectTrait<XmlList>().elementName == nextToken.qualifiedName.name) {
            handledFields.add(flattenedListField)

            return flattenedListField
        }

        // FIXME: The following filter needs to take XML namespace into account when matching.
        // If no attributes are present, take the field matching the serialName
        return descriptor.fields
            .find { it.serialName.toQualifiedName() == nextToken.qualifiedName && !handledFields.contains(it) }
            .also { descriptor -> if (descriptor != null) handledFields.add(descriptor) }
    }

    // Deserializer.FieldIterator
    override fun skipValue() {
        if (!reader.takeIfToken<XmlToken.EndElement>(nodeNameStack)) {
            // Next token was not end, so consume the entire next node.
            reader.skipNext()
        }
    }

    override fun deserializeStruct(descriptor: SdkObjectDescriptor): Deserializer.FieldIterator =
        deserializer.deserializeStruct(descriptor)

    override fun deserializeList(descriptor: SdkFieldDescriptor): Deserializer.ElementIterator =
        deserializer.deserializeList(descriptor)

    override fun deserializeMap(descriptor: SdkFieldDescriptor): Deserializer.EntryIterator =
        deserializer.deserializeMap(descriptor)

    /**
     * Deserialize a byte value defined as the text section of an Xml element.
     *
     */
    override fun deserializeByte(): Byte =
        deserializePrimitive { it.toIntOrNull()?.toByte() }

    /**
     * Deserialize an integer value defined as the text section of an Xml element.
     */
    override fun deserializeInt(): Int =
        deserializePrimitive { it.toIntOrNull() }

    /**
     * Deserialize a short value defined as the text section of an Xml element.
     */
    override fun deserializeShort(): Short =
        deserializePrimitive { it.toIntOrNull()?.toShort() }

    /**
     * Deserialize a long value defined as the text section of an Xml element.
     */
    override fun deserializeLong(): Long =
        deserializePrimitive { it.toLongOrNull() }

    /**
     * Deserialize an float value defined as the text section of an Xml element.
     */
    override fun deserializeFloat(): Float =
        deserializePrimitive { it.toFloatOrNull() }

    /**
     * Deserialize a double value defined as the text section of an Xml element.
     */
    override fun deserializeDouble(): Double =
        deserializePrimitive { it.toDoubleOrNull() }

    /**
     * Deserialize an integer value defined as the text section of an Xml element.
     */
    override fun deserializeString(): String = deserializePrimitive { it }

    /**
     * Deserialize an integer value defined as the text section of an Xml element.
     */
    override fun deserializeBoolean(): Boolean =
        deserializePrimitive { it.toBoolean() }

    override fun deserializeNull(): Nothing? {
        check(reader.takeIfToken<XmlToken.EndElement>(nodeNameStack)) { "Expected XmlToken.EndElement" }
        return null
    }

/*    override fun nextHasValue(): Boolean {
        val nextToken = reader.peekNextToken()

        return nextToken is XmlToken.Text || nextToken is XmlToken.BeginElement
    }*/

    // Read a primitive from either TEXT or an attribute based on value of [attributeParseState].
    private fun <T> deserializePrimitive(transform: (String) -> T?): T {
        val rv = when (attributeParseState) {
            null -> reader.takeToken<XmlToken.Text>(nodeNameStack).value
            is AttributeParseState -> {
                val attfs = attributeParseState!!
                val attribute = XmlToken.QualifiedName(attfs.trait.name, attfs.trait.namespace)
                // This value should only be read once on parse, setting to null to guard unexpected behavior
                attributeParseState = null
                if (attfs.lastFromNode) reader.takeToken<XmlToken.BeginElement>(nodeNameStack)
                attfs.token.attributes[attribute] ?: error("Expected attribute $attribute but was not present")
            }
            else -> error("Unexpected value in nextFieldValueSource $attributeParseState::class")
        }
            ?: throw DeserializationException("Expected value but text of element was null.")

        return transform(rv) ?: throw DeserializationException("Cannot parse $rv with ${transform::class}")
    }
}

private inline fun <reified TExpected : XmlToken> XmlStreamReader.takeUntil(): TExpected {
    var token = this.takeNextToken()
    while (token::class != TExpected::class && token !is XmlToken.EndDocument) {
        token = this.takeNextToken()
    }

    if (token::class != TExpected::class) error("Did not find ${TExpected::class}")
    return token as TExpected
}

// return the next token and require that it be of type [TExpected] or else throw an exception
private inline fun <reified TExpected : XmlToken> XmlStreamReader.takeToken(nodeNameStack: MutableList<XmlToken.QualifiedName>): TExpected {
    val token = this.takeNextToken()
    requireToken<TExpected>(token)

    when (TExpected::class) {
        XmlToken.BeginElement::class -> nodeNameStack.add((token as XmlToken.BeginElement).qualifiedName)
        XmlToken.EndElement::class -> {
            val lastNode = nodeNameStack.removeAt(nodeNameStack.size - 1)
            val currentNodeId = (token as XmlToken.EndElement).qualifiedName
            require(currentNodeId == lastNode) { "Expected to pop $lastNode but found $currentNodeId in $nodeNameStack." }
        }
    }

    return token as TExpected
}

/**
 * If the token is of specified type, consume it and perform work against it in `block`.
 *
 * @param nodeNameStack tracks traversal through tree to verify correctness.
 * @param block function to apply expected token against.
 */
private inline fun <reified TExpected> XmlStreamReader.takeIfToken(
    nodeNameStack: MutableList<XmlToken.QualifiedName>,
    block: (TExpected) -> Unit = {}
): Boolean {
    val nextToken = this.peekNextToken()

    if (nextToken::class == TExpected::class) {
        val expectedToken = this.takeNextToken() as TExpected

        when (TExpected::class) {
            XmlToken.BeginElement::class -> nodeNameStack.add((expectedToken as XmlToken.BeginElement).qualifiedName)
            XmlToken.EndElement::class -> {
                val lastNodeName = nodeNameStack.removeAt(nodeNameStack.size - 1)
                val currentNodeName = (expectedToken as XmlToken.EndElement).qualifiedName
                require(currentNodeName == lastNodeName) { "Expected to pop $lastNodeName but found $currentNodeName in $nodeNameStack." }
            }
        }

        block(expectedToken)

        return true
    }

    return false
}

private fun isContainerType(field: SdkFieldDescriptor?): Boolean {
    return when {
        field is SdkObjectDescriptor -> true
        field?.kind is SerialKind.List -> true
        field?.kind is SerialKind.Map -> true
        field?.kind is SerialKind.Struct -> true
        field == null -> true // Maps to Unknown field, which will be skipped entirely, so treated as a container
        else -> false
    }
}

/**
 * Return the top-level name of the container of a List or Map.
 */
private fun SdkFieldDescriptor.getWrapperName(): String {
    return when (this.kind) {
        is SerialKind.List -> {
            val listTrait = this.expectTrait<XmlList>()
            listTrait.elementName
        }
        is SerialKind.Map -> {
            val mapTrait = this.expectTrait<XmlMap>()
            require(!mapTrait.flattened) { "Cannot get wrapper name of flattened map." }
            mapTrait.entry!!
        }
        else -> error("Unexpected descriptor kind: ${this::class}")
    }
}

/**
 * If the next parse token is BeginElement on List, verify that element matches list wrapper name.
 */
fun XmlStreamReader.consumeListWrapper(
    descriptor: SdkFieldDescriptor,
    nodeNameStack: MutableList<XmlToken.QualifiedName>
) =
    this.takeIfToken<XmlToken.BeginElement>(nodeNameStack) { token ->
        val listInfo = descriptor.expectTrait<XmlList>()
        // NOTE: here we'll need to match on namespace too if we are to de/serialize with namespaces.
        require(token.qualifiedName.name == listInfo.elementName) { "Expected ${listInfo.elementName} but found ${token.qualifiedName}" }
    }
