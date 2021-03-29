/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
import software.aws.clientrt.serde.*
import software.aws.clientrt.serde.SdkFieldDescriptor
import software.aws.clientrt.serde.json.JsonSerdeProvider
import software.aws.clientrt.serde.json.JsonSerializer
import software.aws.clientrt.serde.xml.*
import software.aws.clientrt.testing.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertEquals

@ExperimentalStdlibApi
class SemanticParityTest {

    companion object {
        private val xmlSerdeProvider = XmlSerdeProvider()
        private val jsonSerdeProvider = JsonSerdeProvider()
        fun getTests(): List<CrossProtocolSerdeTest> =
            listOf(BasicStructTest(), ListTest(), MapTest(), NestedStructTest())
    }

    @Test
    fun xmlDeserializesIntoObjectFormThenDeserializesToJsonThenSerializesToObjectFormThenDeserializesToOriginalXml() = runSuspendTest {
        for (test in getTests()) {
            // xml
            val xmlPayload = test.xmlSerialization

            // object
            val xmlDeserializer = xmlSerdeProvider.deserializer(xmlPayload.encodeToByteArray())
            val bst = test.deserialize(xmlDeserializer)

            // json
            val jsonSerializer = JsonSerializer()
            bst.serialize(jsonSerializer)
            val jsonPayload = jsonSerializer.toByteArray().decodeToString()

            // object
            val jsonDeserializer = jsonSerdeProvider.deserializer(jsonPayload.encodeToByteArray())
            val bst2 = test.deserialize(jsonDeserializer)

            assertEquals(bst, bst2)

            // xml - compare
            val xmlSerializer = XmlSerializer()
            bst2.serialize(xmlSerializer)
            val xmlPayload2 = xmlSerializer.toByteArray().decodeToString()

            assertEquals(xmlPayload, xmlPayload2)
        }
    }

    @Test
    fun jsonDeserializesIntoObjectFormThenDeserializesToXmlThenSerializesToObjectFormThenDeserializesToOriginalJson() = runSuspendTest {
        for (test in getTests()) {
            // json
            val jsonPayload = test.jsonSerialization

            // object
            val jsonDeserializer = jsonSerdeProvider.deserializer(jsonPayload.encodeToByteArray())
            val bst = test.deserialize(jsonDeserializer)

            // xml
            val xmlSerializer = XmlSerializer()
            bst.serialize(xmlSerializer)
            val xmlPayload = xmlSerializer.toByteArray().decodeToString()

            // object
            val xmlDeserializer = xmlSerdeProvider.deserializer(xmlPayload.encodeToByteArray())
            val bst2 = test.deserialize(xmlDeserializer)

            assertEquals(bst, bst2)

            // json - compare
            val jsonSerializer = JsonSerializer()
            bst2.serialize(jsonSerializer)
            val jsonPayload2 = jsonSerializer.toByteArray().decodeToString()

            assertEquals(jsonPayload, jsonPayload2)
        }
    }

    @Test
    fun objectFormSerializesIntoEquivalentRepresentationsInJsonAndXml() {
        for (test in getTests()) {
            val bst = test.sdkSerializable

            val xmlSerializer = XmlSerializer()
            bst.serialize(xmlSerializer)
            val xml = xmlSerializer.toByteArray().decodeToString()

            val jsonSerializer = JsonSerializer()
            bst.serialize(jsonSerializer)
            val json = jsonSerializer.toByteArray().decodeToString()

            val jsonPayload = test.jsonSerialization
            val xmlPayload = test.xmlSerialization

            assertEquals(xml, xmlPayload)
            assertEquals(json, jsonPayload)
        }
    }

    @Test
    fun equivalentJsonAndXmlSerialFormsProduceTheSameObjectForm() = runSuspendTest {
        for (test in getTests()) {
            val jsonDeserializer = jsonSerdeProvider.deserializer(test.jsonSerialization.encodeToByteArray())
            val jsonBst = test.deserialize(jsonDeserializer)

            val xmlDeserializer = xmlSerdeProvider.deserializer(test.xmlSerialization.encodeToByteArray())
            val xmlBst = test.deserialize(xmlDeserializer)

            assertEquals(jsonBst, xmlBst)
        }
    }

    @Test
    fun itDeserializesFromJsonAndThenSerializesToXml() = runSuspendTest {
        for (test in getTests()) {
            val jsonDeserializer = jsonSerdeProvider.deserializer(test.jsonSerialization.encodeToByteArray())
            val bst = test.deserialize(jsonDeserializer)

            val xmlSerializer = XmlSerializer()
            bst.serialize(xmlSerializer)

            assertEquals(test.xmlSerialization, xmlSerializer.toByteArray().decodeToString())
        }
    }

    interface CrossProtocolSerdeTest {
        val jsonSerialization: String
        val xmlSerialization: String
        val sdkSerializable: SdkSerializable
        suspend fun deserialize(deserializer: Deserializer): SdkSerializable
    }

    data class BasicStructTest(var x: Int? = null, var y: String? = null, var z: Boolean? = null) :
        SdkSerializable,
        CrossProtocolSerdeTest {

        companion object {
            val X_DESCRIPTOR = SdkFieldDescriptor(SerialKind.Integer, "x".toSerialNames())
            val Y_DESCRIPTOR = SdkFieldDescriptor(SerialKind.String, "y".toSerialNames())
            val Z_DESCRIPTOR = SdkFieldDescriptor(SerialKind.Boolean, "z".toSerialNames())
            val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
                trait(XmlSerialName("payload"))
                field(X_DESCRIPTOR)
                field(Y_DESCRIPTOR)
                field(Z_DESCRIPTOR)
            }

            suspend fun deserialize(deserializer: Deserializer): BasicStructTest {
                val result = BasicStructTest()
                deserializer.deserializeStruct(OBJ_DESCRIPTOR) {
                    loop@ while (true) {
                        when (findNextFieldIndex()) {
                            X_DESCRIPTOR.index -> result.x = deserializeInt()
                            Y_DESCRIPTOR.index -> result.y = deserializeString()
                            Z_DESCRIPTOR.index -> result.z = deserializeBoolean()
                            null -> break@loop
                            else -> throw RuntimeException("unexpected field in BasicStructTest deserializer")
                        }
                    }
                }
                return result
            }
        }

        override fun serialize(serializer: Serializer) {
            serializer.serializeStruct(OBJ_DESCRIPTOR) {
                field(X_DESCRIPTOR, x!!)
                field(Y_DESCRIPTOR, y!!)
                field(Z_DESCRIPTOR, z!!)
            }
        }

        override val jsonSerialization: String
            get() = """{"x":1,"y":"two","z":true}"""
        override val xmlSerialization: String
            get() = "<payload><x>1</x><y>two</y><z>true</z></payload>"
        override val sdkSerializable: SdkSerializable
            get() = BasicStructTest(1, "two", true)

        override suspend fun deserialize(deserializer: Deserializer): SdkSerializable =
            BasicStructTest.deserialize(deserializer)
    }

    data class ListTest(var intList: List<Int>? = null) : SdkSerializable, CrossProtocolSerdeTest {
        companion object {
            val LIST_DESCRIPTOR = SdkFieldDescriptor(SerialKind.List, "list".toSerialNames())
            val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
                trait(XmlSerialName("payload"))
                field(LIST_DESCRIPTOR)
            }

            suspend fun deserialize(deserializer: Deserializer): ListTest {
                val result = ListTest()
                deserializer.deserializeStruct(OBJ_DESCRIPTOR) {
                    loop@ while (true) {
                        when (findNextFieldIndex()) {
                            LIST_DESCRIPTOR.index -> result.intList = deserializer.deserializeList(LIST_DESCRIPTOR) {
                                val intList = mutableListOf<Int>()
                                while (this.hasNextElement()) {
                                    intList.add(this.deserializeInt())
                                }
                                result.intList = intList
                                return@deserializeList intList
                            }
                            null -> break@loop
                            else -> throw RuntimeException("unexpected field in BasicStructTest deserializer")
                        }
                    }
                }
                return result
            }
        }

        override fun serialize(serializer: Serializer) {
            serializer.serializeStruct(OBJ_DESCRIPTOR) {
                listField(LIST_DESCRIPTOR) {
                    for (value in intList!!) {
                        serializeInt(value)
                    }
                }
            }
        }

        override val jsonSerialization: String
            get() = """{"list":[1,2,3,10]}"""
        override val xmlSerialization: String
            get() = "<payload><list><member>1</member><member>2</member><member>3</member><member>10</member></list></payload>"
        override val sdkSerializable: SdkSerializable
            get() = ListTest(listOf(1, 2, 3, 10))

        override suspend fun deserialize(deserializer: Deserializer): SdkSerializable =
            ListTest.deserialize(deserializer)
    }

    data class MapTest(var strMap: Map<String, String>? = null) : SdkSerializable, CrossProtocolSerdeTest {
        companion object {
            val MAP_DESCRIPTOR = SdkFieldDescriptor(SerialKind.Map, "map".toSerialNames())
            val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
                trait(XmlSerialName("payload"))
                field(MAP_DESCRIPTOR)
            }

            suspend fun deserialize(deserializer: Deserializer): MapTest {
                val result = MapTest()
                deserializer.deserializeStruct(OBJ_DESCRIPTOR) {
                    loop@ while (true) {
                        when (findNextFieldIndex()) {
                            MAP_DESCRIPTOR.index -> result.strMap = deserializer.deserializeMap(MAP_DESCRIPTOR) {
                                val map = mutableMapOf<String, String>()
                                while (this.hasNextEntry()) {
                                    map[key()] = deserializeString()
                                }
                                result.strMap = map
                                return@deserializeMap map
                            }
                            null -> break@loop
                            else -> throw RuntimeException("unexpected field in BasicStructTest deserializer")
                        }
                    }
                }
                return result
            }
        }

        override fun serialize(serializer: Serializer) {
            serializer.serializeStruct(OBJ_DESCRIPTOR) {
                mapField(MAP_DESCRIPTOR) {
                    for (e in strMap!!) {
                        entry(e.key, e.value)
                    }
                }
            }
        }

        override val jsonSerialization: String
            get() = """{"map":{"key1":"val1","key2":"val2","key3":"val3"}}"""
        override val xmlSerialization: String
            get() = "<payload><map><entry><key>key1</key><value>val1</value></entry><entry><key>key2</key><value>val2</value></entry><entry><key>key3</key><value>val3</value></entry></map></payload>"
        override val sdkSerializable: SdkSerializable
            get() = MapTest(mapOf("key1" to "val1", "key2" to "val2", "key3" to "val3"))

        override suspend fun deserialize(deserializer: Deserializer): SdkSerializable =
            MapTest.deserialize(deserializer)
    }

    data class NestedStructTest(var nested: BasicStructTest? = null) :
        SdkSerializable,
        CrossProtocolSerdeTest {

        companion object {
            val NESTED_STRUCT_DESCRIPTOR = SdkFieldDescriptor(SerialKind.Struct, "payload".toSerialNames())
            val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
                trait(XmlSerialName("outerpayload"))
                field(NESTED_STRUCT_DESCRIPTOR)
            }

            suspend fun deserialize(deserializer: Deserializer): NestedStructTest {
                val result = NestedStructTest()
                deserializer.deserializeStruct(OBJ_DESCRIPTOR) {
                    loop@ while (true) {
                        when (findNextFieldIndex()) {
                            NESTED_STRUCT_DESCRIPTOR.index -> result.nested = BasicStructTest.deserialize(deserializer)
                            null -> break@loop
                            else -> throw RuntimeException("unexpected field in BasicStructTest deserializer")
                        }
                    }
                }
                return result
            }
        }

        override fun serialize(serializer: Serializer) {
            serializer.serializeStruct(OBJ_DESCRIPTOR) {
                field(NESTED_STRUCT_DESCRIPTOR, nested!!)
            }
        }

        override val jsonSerialization: String
            get() = """{"payload":{"x":1,"y":"two","z":true}}"""
        override val xmlSerialization: String
            get() = "<outerpayload><payload><x>1</x><y>two</y><z>true</z></payload></outerpayload>"
        override val sdkSerializable: SdkSerializable
            get() = NestedStructTest(BasicStructTest(1, "two", true))

        override suspend fun deserialize(deserializer: Deserializer): SdkSerializable =
            NestedStructTest.deserialize(deserializer)
    }
}
