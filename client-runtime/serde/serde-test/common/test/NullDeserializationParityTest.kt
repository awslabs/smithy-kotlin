import software.aws.clientrt.serde.*
import software.aws.clientrt.serde.json.JsonDeserializer
import software.aws.clientrt.serde.json.SerialName
import software.aws.clientrt.serde.json.fromSerialName
import software.aws.clientrt.serde.xml.XmlDeserializer
import software.aws.clientrt.serde.xml.XmlNamespace
import software.aws.clientrt.serde.xml.XmlSerialName
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

var SdkObjectDescriptor.DslBuilder.serialName
    get(): String = error { "Should not be called" }
    set(value) = trait(XmlSerialName(value))

fun SdkFieldDescriptor(name: String, kind: SerialKind, index: Int = 0, trait: FieldTrait? = null): SdkFieldDescriptor {
    val xmlSerialName = if (name.contains(':')) {
        val (name, namespace) = name.split(':')
        XmlSerialName(name, XmlNamespace(namespace, "https://someuri"))
    } else {
        XmlSerialName(name)
    }

    val jsonSerialName = SerialName(name)

    return if (trait != null)
        SdkFieldDescriptor(kind = kind, index = index, traits = setOf(xmlSerialName, jsonSerialName, trait))
    else
        SdkFieldDescriptor(kind = kind, index = index, traits = setOf(xmlSerialName, jsonSerialName))
}

class NullDeserializationParityTest {

    public fun SdkFieldDescriptor.Companion.multiType(name: String, kind: SerialKind, namespace: String?): SdkFieldDescriptor {
        val traits = mutableSetOf<FieldTrait>()
        traits.add(SerialName(name))
        traits.add(XmlSerialName(name, if (namespace != null) XmlNamespace(namespace, "https://someuri") else null))
        return SdkFieldDescriptor(kind = kind, traits = traits)
    }

    class AnonStruct {
        var x: Int? = null
        var y: Int? = null
        companion object {
            val X_DESCRIPTOR = SdkFieldDescriptor("x", SerialKind.Integer)
            val Y_DESCRIPTOR = SdkFieldDescriptor("y", SerialKind.Integer)
            val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
                serialName = "AnonStruct"
                field(X_DESCRIPTOR)
                field(Y_DESCRIPTOR)
            }

            fun deserialize(deserializer: Deserializer): AnonStruct {
                val result = AnonStruct()
                deserializer.deserializeStruct(OBJ_DESCRIPTOR) {
                    loop@ while (true) {
                        when (findNextFieldIndex()) {
                            X_DESCRIPTOR.index -> result.x = deserializeInt()
                            Y_DESCRIPTOR.index -> result.y = deserializeInt()
                            null -> break@loop
                            else -> throw RuntimeException("unexpected field in BasicStructTest deserializer")
                        }
                    }
                }
                return result
            }
        }
    }

    class ParentStruct {
        var childStruct: ChildStruct? = null

        companion object {
            val X_DESCRIPTOR = SdkFieldDescriptor("ChildStruct", SerialKind.Map)
            val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
                field(X_DESCRIPTOR)
            }

            fun deserialize(deserializer: Deserializer): ParentStruct {
                val result = ParentStruct()
                deserializer.deserializeStruct(OBJ_DESCRIPTOR) {
                    loop@ while (true) {
                        when (findNextFieldIndex()) {
                            X_DESCRIPTOR.index -> result.childStruct = ChildStruct.deserialize(deserializer)
                            null -> break@loop
                            else -> throw RuntimeException("unexpected field in BasicStructTest deserializer")
                        }
                    }
                }
                return result
            }
        }
    }

    class ChildStruct {
        var x: Int? = null
        var y: Int? = null
        companion object {
            val X_DESCRIPTOR = SdkFieldDescriptor("x", SerialKind.Integer)
            val Y_DESCRIPTOR = SdkFieldDescriptor("y", SerialKind.Integer)
            val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
                serialName = "ChildStruct"
                field(X_DESCRIPTOR)
                field(Y_DESCRIPTOR)
            }

            fun deserialize(deserializer: Deserializer): ChildStruct {
                val result = ChildStruct()
                deserializer.deserializeStruct(OBJ_DESCRIPTOR) {
                    loop@ while (true) {
                        when (findNextFieldIndex()) {
                            X_DESCRIPTOR.index -> result.x = deserializeInt()
                            Y_DESCRIPTOR.index -> result.y = deserializeInt()
                            null -> break@loop
                            else -> throw RuntimeException("unexpected field in ChildStruct deserializer")
                        }
                    }
                }
                return result
            }
        }
    }

    /**
     * Empty objects should deserialize into empty instances of their target type.
     */
    @Test
    fun itDeserializesAnEmptyDocumentIntoAnEmptyAnonymousStruct() {
        val jsonPayload = "{}".encodeToByteArray()
        val xmlPayload = "<AnonStruct />".encodeToByteArray()

        for (deserializer in listOf(JsonDeserializer(jsonPayload), XmlDeserializer(xmlPayload))) {
            val struct = AnonStruct.deserialize(deserializer)

            assertNotNull(struct)
            assertNull(struct.x)
            assertNull(struct.y)
        }
    }

    /**
     * Inputs that specify the value of an object as null, or do not reference the child at all should
     * deserialize those children as null references.
     */
    @Test
    fun itDeserializesAReferenceToANullObject() {
        val jsonPayload = """
            { "ChildStruct" : null }
        """.trimIndent().encodeToByteArray()
        val xmlPayload = """
            <ParentStruct />                
        """.trimIndent().encodeToByteArray()

        for (deserializer in listOf(JsonDeserializer(jsonPayload), XmlDeserializer(xmlPayload))) {
            val struct = ParentStruct.deserialize(deserializer)

            assertNotNull(struct)
            assertNull(struct.childStruct)
        }
    }

    /**
     * Inputs that refer to children as empty elements should deserialize such that
     * those deserialized children exist but are empty.
     */
    @Test
    fun itDeserializesAReferenceToAnEmptyObject() {
        val jsonPayload = """
            { "ChildStruct" : {}} }
        """.trimIndent().encodeToByteArray()
        val xmlPayload = """
            <ParentStruct>
                <ChildStruct />
            </ParentStruct>
        """.trimIndent().encodeToByteArray()

        for (deserializer in listOf(JsonDeserializer(jsonPayload), XmlDeserializer(xmlPayload))) {
            val struct = ParentStruct.deserialize(deserializer)

            assertNotNull(struct)
            assertNotNull(struct.childStruct)
            assertNull(struct.childStruct!!.x)
            assertNull(struct.childStruct!!.y)
        }
    }
}
