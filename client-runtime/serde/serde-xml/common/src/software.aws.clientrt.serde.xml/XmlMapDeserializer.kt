package software.aws.clientrt.serde.xml

import software.aws.clientrt.serde.*

internal class XmlMapDeserializer(
    private val fieldDescriptor: SdkFieldDescriptor,
    private val reader: XmlStreamReader,
    private val parentDeserializer: XmlStructDeserializer,
    primitiveDeserializer: XmlPrimitiveDeserializer
) : Deserializer.EntryIterator, PrimitiveDeserializer by primitiveDeserializer {

    override suspend fun hasNextEntry(): Boolean = when (reader.peek()) {
        is XmlToken.EndDocument -> throw DeserializerStateException("Unexpected end of document.")
        is XmlToken.EndElement -> {
            reader.takeNextAs<XmlToken.EndElement>()
            if (!fieldDescriptor.hasTrait<Flattened>() && reader.peek() is XmlToken.EndElement) {
                reader.takeNextAs<XmlToken.EndElement>()
            }

            val hasNext = reader.peek() is XmlToken.BeginElement
            if (!hasNext) parentDeserializer.clearParsedFields()
            hasNext
        }
        else -> true
    }

    override suspend fun key(): String {
        val mapTrait = fieldDescriptor.findTrait() ?: XmlMapName.DEFAULT
        reader.takeUntil<XmlToken.BeginElement> { it.qualifiedName.name == mapTrait.key } ?: throw DeserializerStateException("Expected node named ${mapTrait.key} but found ${reader.currentToken}")
        val keyValue = reader.takeNextAs<XmlToken.Text>()

        if (keyValue.value == null || keyValue.value.isBlank()) throw DeserializerStateException("Expected String value for key but was empty: ${reader.currentToken}")
        if (reader.takeNextAs<XmlToken.EndElement>().qualifiedName.name != mapTrait.key) throw DeserializerStateException("Expected end tag ${mapTrait.key} for key field")

        return keyValue.value
    }

    override suspend fun nextHasValue(): Boolean {
        val valueWrapperToken = reader.takeNextAs<XmlToken.BeginElement>()
        val mapTrait = fieldDescriptor.findTrait() ?: XmlMapName.DEFAULT

        if (valueWrapperToken.qualifiedName.name != mapTrait.value) throw DeserializerStateException("Expected map value ${mapTrait.value} but found ${valueWrapperToken.qualifiedName}")

        val nextToken = reader.peek()

        return nextToken is XmlToken.Text || nextToken is XmlToken.BeginElement
    }
}
