package software.aws.clientrt.serde.xml

import software.aws.clientrt.serde.*
import kotlin.contracts.contract

class XmlDeserializer2(private val reader: XmlStreamReader) : Deserializer {

    data class StructDeserializerInstance(val structSerializer: XmlStructDeserializer, val parseLevel: Int)

    private var structDeserializerStack = mutableListOf<StructDeserializerInstance>()

    constructor(input: ByteArray) : this(xmlStreamReader(input))

    override fun deserializeStruct(descriptor: SdkObjectDescriptor): Deserializer.FieldIterator {
        return when {
            structDeserializerStack.isEmpty() -> { // Root deserializer
                reader.takeUntil<XmlToken.BeginElement>()

                val structSerializer = XmlStructDeserializer(descriptor, reader)
                structDeserializerStack.add(StructDeserializerInstance(structSerializer, reader.currentDepth))
                structSerializer
            }
            else -> { // Nested deserializer
                // Flush existing token to avoid revisiting same node upon return
                // This is safe because attributes are always processed before children
                cleanupDeserializerStack()
                structDeserializerStack.last().structSerializer.clearNodeValueTokens()

                // Optionally consume next token until we match our objectDescriptor.
                // This can vary depending on where deserializeStruct() is called from (list/map vs struct)
                var token = if (reader.currentToken is XmlToken.BeginElement)
                    reader.currentToken as XmlToken.BeginElement
                else
                    reader.takeUntil()

                val targetTokenName = descriptor.expectTrait<XmlSerialName>().name
                while (token.qualifiedName.name != targetTokenName) token =
                    reader.takeNextTokenOf<XmlToken.BeginElement>()

                val structSerializer = XmlStructDeserializer(descriptor, reader)
                structDeserializerStack.add(StructDeserializerInstance(structSerializer, reader.currentDepth))
                structSerializer
            }
        }
    }

    override fun deserializeList(descriptor: SdkFieldDescriptor): Deserializer.ElementIterator {
        check(structDeserializerStack.isNotEmpty()) { "List cannot be deserialized independently from a parent struct" }
        cleanupDeserializerStack()
        return XmlListDeserializer(
            descriptor,
            reader,
            structDeserializerStack.last().structSerializer,
            primitiveDeserializer = XmlPrimitiveDeserializer(reader, descriptor)
        )
    }

    override fun deserializeMap(descriptor: SdkFieldDescriptor): Deserializer.EntryIterator {
        check(structDeserializerStack.isNotEmpty()) { "Map cannot be deserialized independently from a parent struct" }
        cleanupDeserializerStack()
        return XmlMapDeserializer(
            descriptor,
            reader,
            structDeserializerStack.last().structSerializer,
            primitiveDeserializer = XmlPrimitiveDeserializer(reader, descriptor)
        )
    }

    // Each struct deserializer maintains a set of NodeValueTokens.  When structs
    // traverse into other container, these tokens need to be cleared.
    private fun cleanupDeserializerStack() {
        var pair = structDeserializerStack.lastOrNull()

        while (pair != null && pair.parseLevel >= reader.currentDepth) {
            pair.structSerializer.clearNodeValueTokens()
            structDeserializerStack.remove(pair)
            pair = structDeserializerStack.lastOrNull()
        }
        check(structDeserializerStack.isNotEmpty()) { "root deserializer should never be removed" }
    }
}

internal inline fun <reified TExpected : XmlToken> XmlStreamReader.takeUntil(): TExpected {
    var token = this.takeNextToken()
    while (token::class != TExpected::class && token !is XmlToken.EndDocument) {
        token = this.takeNextToken()
    }

    if (token::class != TExpected::class) throw DeserializerStateException("Did not find ${TExpected::class}")
    return token as TExpected
}

internal inline fun <reified TExpected : XmlToken> XmlStreamReader.takeNextTokenOf(): TExpected {
    val token = this.takeNextToken()
    requireToken<TExpected>(token)
    return token as TExpected
}

// Reads the stream while until a node is not the specified type or the predicate returns true.
// Returns null if a different node was found or the node that matches the predicate.
internal inline fun <reified TExpected : XmlToken> XmlStreamReader.takeAllUntil(predicate: (TExpected) -> Boolean): TExpected? {
    var token = takeNextToken()

    while (tokenIsType<TExpected>(token) && !predicate.invoke(token as TExpected)) {
        token = takeNextToken()
    }

    return if (tokenIsType<TExpected>(token)) token as TExpected else null
}

// require that the given token be of type [TExpected] or else throw an exception
internal inline fun <reified TExpected> tokenIsType(token: XmlToken) = token::class == TExpected::class

// require that the given token be of type [TExpected] or else throw an exception
internal inline fun <reified TExpected> requireToken(token: XmlToken) {
    if (token::class != TExpected::class) {
        throw DeserializerStateException("expected ${TExpected::class}; found ${token::class} ($token)")
    }
}

// like check() but throws deserializer-specific exception for state problems
internal inline fun checkDeserializerState(value: Boolean, lazyMessage: () -> Any): Unit {
    if (!value) {
        val message = lazyMessage()
        throw DeserializerStateException(message.toString())
    }
}