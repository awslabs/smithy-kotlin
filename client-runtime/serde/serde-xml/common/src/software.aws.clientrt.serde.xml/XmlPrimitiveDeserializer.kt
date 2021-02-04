package software.aws.clientrt.serde.xml

import software.aws.clientrt.serde.DeserializationException
import software.aws.clientrt.serde.PrimitiveDeserializer
import software.aws.clientrt.serde.SdkFieldDescriptor

/**
 * Deserialize primitive values for single values, lists, and maps
 */
class XmlPrimitiveDeserializer(private val reader: XmlStreamReader, private val fieldDescriptor: SdkFieldDescriptor):
    PrimitiveDeserializer {

    constructor(input: ByteArray, fieldDescriptor: SdkFieldDescriptor) : this(xmlStreamReader(input), fieldDescriptor)

    private fun <T> deserializeValue(transform: ((String) -> T)): T {
        if (reader.peekNextToken() is XmlToken.BeginElement) {
            // In the case of flattened lists, we "fall" into the first node as there is no wrapper.
            // this conditional checks that case for the first element of the list.
            val wrapperToken = reader.takeNextTokenOf<XmlToken.BeginElement>()
            if (wrapperToken.qualifiedName.name != fieldDescriptor.generalName()) {
                //Depending on flat/not-flat, may need to consume multiple start nodes
                return deserializeValue(transform)
            }
        }

        val token = reader.takeNextTokenOf<XmlToken.Text>()

        val returnValue = token.value?.let { transform(it) }?.also {
            reader.takeNextTokenOf<XmlToken.EndElement>()
        } ?: error("wtf")

        if (fieldDescriptor.hasTrait<XmlMap>()) {
            //Optionally consume the entry wrapper
            val mapTrait = fieldDescriptor.expectTrait<XmlMap>()
            val nextToken = reader.peekNextToken()
            if (nextToken is XmlToken.EndElement) {
                val consumeEndToken = when (mapTrait.flattened) {
                    true -> nextToken.qualifiedName.name == fieldDescriptor.expectTrait<XmlSerialName>().name
                    false -> nextToken.qualifiedName.name == mapTrait.entry
                }
                if (consumeEndToken) reader.takeNextTokenOf<XmlToken.EndElement>()
            }
        }

        return returnValue
    }

    override fun deserializeByte(): Byte = deserializeValue { it.toIntOrNull()?.toByte()?: throw DeserializationException("Unable to deserialize $it") }

    override fun deserializeInt(): Int = deserializeValue { it.toIntOrNull() ?: throw DeserializationException("Unable to deserialize $it") }

    override fun deserializeShort(): Short = deserializeValue { it.toIntOrNull()?.toShort()?: throw DeserializationException("Unable to deserialize $it") }

    override fun deserializeLong(): Long = deserializeValue { it.toLongOrNull()?: throw DeserializationException("Unable to deserialize $it") }

    override fun deserializeFloat(): Float = deserializeValue { it.toFloatOrNull()?: throw DeserializationException("Unable to deserialize $it") }

    override fun deserializeDouble(): Double = deserializeValue { it.toDoubleOrNull()?: throw DeserializationException("Unable to deserialize $it") }

    override fun deserializeString(): String = deserializeValue { it }

    override fun deserializeBoolean(): Boolean = deserializeValue { it.toBoolean() }

    override fun deserializeNull(): Nothing? {
        reader.takeNextTokenOf<XmlToken.EndElement>()
        return null
    }
}