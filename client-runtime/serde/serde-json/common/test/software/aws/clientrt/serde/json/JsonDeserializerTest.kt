/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package software.aws.clientrt.serde.json

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import software.aws.clientrt.serde.*
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalStdlibApi::class)
class JsonDeserializerTest {
    @Test
    fun itHandlesDoubles() {
        val payload = "1.2".encodeToByteArray()
        val deserializer = JsonDeserializer(payload)
        val actual = deserializer.deserializeDouble()
        val expected = 1.2
        assertNotNull(actual)
        assertTrue(abs(actual - expected) <= 0.0001)
    }

    @Test
    fun itHandlesFloats() {
        val payload = "1.2".encodeToByteArray()
        val deserializer = JsonDeserializer(payload)
        val actual = deserializer.deserializeFloat()
        val expected = 1.2f
        assertNotNull(actual)
        assertTrue(abs(actual - expected) <= 0.0001f)
    }

    @Test
    fun itHandlesInt() {
        val payload = "1.2".encodeToByteArray()
        val deserializer = JsonDeserializer(payload)
        val actual = deserializer.deserializeInt()
        val expected = 1
        assertEquals(expected, actual)
    }

    @Test
    fun itHandlesByteAsNumber() {
        val payload = "1".encodeToByteArray()
        val deserializer = JsonDeserializer(payload)
        val actual = deserializer.deserializeByte()
        val expected: Byte = 1
        assertEquals(expected, actual)
    }

    @Test
    fun itHandlesShort() {
        val payload = "1.2".encodeToByteArray()
        val deserializer = JsonDeserializer(payload)
        val actual = deserializer.deserializeShort()
        val expected: Short = 1
        assertEquals(expected, actual)
    }

    @Test
    fun itHandlesLong() {
        val payload = "1.2".encodeToByteArray()
        val deserializer = JsonDeserializer(payload)
        val actual = deserializer.deserializeLong()
        val expected = 1L
        assertEquals(expected, actual)
    }

    @Test
    fun itHandlesBool() {
        val payload = "true".encodeToByteArray()
        val deserializer = JsonDeserializer(payload)
        val actual = deserializer.deserializeBoolean()
        assertNotNull(actual)
        assertTrue(actual)
    }

    @Test
    fun itHandlesString() {
        // allow deserializeString() to consume tokens other than JsonToken.String as raw string values
        // this supports custom deserialization (e.g. timestamps) of the raw value
        val tests = listOf(
            "\"hello\"",
            "1",
            "12.7",
            "true",
            "false"
        )

        for (test in tests) {
            val payload = test.encodeToByteArray()
            val deserializer = JsonDeserializer(payload)
            val actual = deserializer.deserializeString()
            assertEquals(test.removeSurrounding("\""), actual)
        }
    }

    @Test
    fun itHandlesNull() {
        val payload = "null".encodeToByteArray()
        val stringDeserializer = JsonDeserializer(payload)
        stringDeserializer.deserializeNull()

        val boolDeserializer = JsonDeserializer(payload)
        boolDeserializer.deserializeNull()
    }

    @Test
    fun itHandlesLists() {
        val payload = "[1,2,3]".encodeToByteArray()
        val deserializer = JsonDeserializer(payload)
        val actual = deserializer.deserializeList(SdkFieldDescriptor(SerialKind.List)) {
            val list = mutableListOf<Int>()
            while (hasNextElement()) {
                list.add(deserializeInt())
            }
            return@deserializeList list
        }
        val expected = listOf(1, 2, 3)
        actual.shouldContainExactly(expected)
    }

    @Test
    fun itHandlesSparseLists() {
        val payload = "[1,null,3]".encodeToByteArray()
        val deserializer = JsonDeserializer(payload)
        val actual = deserializer.deserializeList(SdkFieldDescriptor(SerialKind.List)) {
            val list = mutableListOf<Int?>()
            while (hasNextElement()) {
                val element = if (nextHasValue()) deserializeInt() else deserializeNull()
                list.add(element)
            }
            return@deserializeList list
        }
        val expected = listOf(1, null, 3)
        actual.shouldContainExactly(expected)
    }

    @Test
    fun itHandlesMaps() {
        val payload = """
            {
                "key1": 1,
                "key2": 2
            }
        """.trimIndent().encodeToByteArray()
        val deserializer: Deserializer = JsonDeserializer(payload)
        val actual = deserializer.deserializeMap(SdkFieldDescriptor(SerialKind.Map)) {
            val map = mutableMapOf<String, Int>()
            while (hasNextEntry()) {
                map[key()] = deserializeInt()
            }
            return@deserializeMap map
        }
        val expected = mapOf("key1" to 1, "key2" to 2)
        actual.shouldContainExactly(expected)
    }

    @Test
    fun itChecksNullValuesOfNonSparseMaps() {
        val payload = """
            {
                "key1": 1,
                "key2": null,
                "key3": 3
            }
        """.trimIndent().encodeToByteArray()
        val deserializer: Deserializer = JsonDeserializer(payload)
        val actual = deserializer.deserializeMap(SdkFieldDescriptor(SerialKind.Map)) {
            val map = mutableMapOf<String, Int>()
            while (hasNextEntry()) {
                val key = key()
                if (nextHasValue()) {
                    map[key] = deserializeInt()
                } else {
                    deserializeNull()
                }
            }
            return@deserializeMap map
        }
        val expected = mapOf("key1" to 1, "key3" to 3)
        actual.shouldContainExactly(expected)
    }

    class BasicStructTest {
        var x: Int? = null
        var y: Int? = null
        companion object {
            val X_DESCRIPTOR = SdkFieldDescriptor.fromSerialName("x", SerialKind.Integer)
            val Y_DESCRIPTOR = SdkFieldDescriptor.fromSerialName("y", SerialKind.Integer)
            val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
                field(X_DESCRIPTOR)
                field(Y_DESCRIPTOR)
            }

            fun deserialize(deserializer: Deserializer): BasicStructTest {
                val result = BasicStructTest()
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

    @Test
    fun itHandlesBasicStructs() {
        val payload = """
        {
            "x": 1,
            "y": 2
        }
        """.trimIndent().encodeToByteArray()

        val deserializer = JsonDeserializer(payload)
        var x: Int? = null
        var y: Int? = null
        deserializer.deserializeStruct(BasicStructTest.OBJ_DESCRIPTOR) {
            loop@ while (true) {
                when (findNextFieldIndex()) {
                    BasicStructTest.X_DESCRIPTOR.index -> x = deserializeInt()
                    BasicStructTest.Y_DESCRIPTOR.index -> y = deserializeInt()
                    null -> break@loop
                }
            }
        }
        assertEquals(1, x)
        assertEquals(2, y)
    }

    @Test
    fun itHandlesListOfObjects() {
        val payload = """
        [
            {
                "x": 1,
                "y": 2
            },
            {
                "x": 3,
                "y": 4
            }
        ]
        """.trimIndent().encodeToByteArray()
        val deserializer = JsonDeserializer(payload)
        val actual = deserializer.deserializeList(SdkFieldDescriptor(SerialKind.List)) {
            val list = mutableListOf<BasicStructTest>()
            while (hasNextElement()) {
                list.add(BasicStructTest.deserialize(deserializer))
            }
            return@deserializeList list
        }
        assertEquals(2, actual.size)
        assertEquals(1, actual[0].x)
        assertEquals(2, actual[0].y)
        assertEquals(3, actual[1].x)
        assertEquals(4, actual[1].y)
    }

    @Test
    fun itEnumeratesUnknownStructFields() {
        val payload = """
        {
            "x": 1,
            "z": "unknown field",
            "y": 2
        }
        """.trimIndent().encodeToByteArray()

        val deserializer = JsonDeserializer(payload)
        val struct = deserializer.deserializeStruct(BasicStructTest.OBJ_DESCRIPTOR)
        var found = false
        loop@ while (true) {
            when (struct.findNextFieldIndex()) {
                Deserializer.FieldIterator.UNKNOWN_FIELD -> {
                    found = true
                    struct.skipValue()
                }
                null -> break@loop
                // still have to advance the deserializer
                else -> struct.skipValue()
            }
        }
        assertTrue(found, "unknown field not enumerated")
    }

    class Nested2 {
        var list2: List<String>? = null
        var int2: Int? = null
        companion object {
            val LIST2_FIELD_DESCRIPTOR = SdkFieldDescriptor.fromSerialName("list2", SerialKind.List)
            val INT2_FIELD_DESCRIPTOR = SdkFieldDescriptor.fromSerialName("int2", SerialKind.Integer)
            val OBJ_DESCRIPTOR = SdkObjectDescriptor.build() {
                field(LIST2_FIELD_DESCRIPTOR)
                field(INT2_FIELD_DESCRIPTOR)
            }

            fun deserialize(deserializer: Deserializer): Nested2 {
                val struct = deserializer.deserializeStruct(OBJ_DESCRIPTOR)
                val nested2 = Nested2()
                loop@ while (true) {
                    when (struct.findNextFieldIndex()) {
                        LIST2_FIELD_DESCRIPTOR.index -> nested2.list2 = deserializer.deserializeList(LIST2_FIELD_DESCRIPTOR) {
                            val list = mutableListOf<String>()
                            while (hasNextElement()) {
                                list.add(deserializeString())
                            }
                            return@deserializeList list
                        }
                        INT2_FIELD_DESCRIPTOR.index -> nested2.int2 = struct.deserializeInt()
                        // deeply nested unknown field
                        Deserializer.FieldIterator.UNKNOWN_FIELD -> struct.skipValue()
                        null -> break@loop
                        else -> throw RuntimeException("unexpected field during test")
                    }
                }
                return nested2
            }
        }
    }

    class Nested {
        var nested2: Nested2? = null
        var bool2: Boolean? = null

        companion object {
            val NESTED2_FIELD_DESCRIPTOR = SdkFieldDescriptor.fromSerialName("nested2", SerialKind.Struct)
            val BOOL2_FIELD_DESCRIPTOR = SdkFieldDescriptor.fromSerialName("bool2", SerialKind.Boolean)
            val OBJ_DESCRIPTOR = SdkObjectDescriptor.build() {
                field(NESTED2_FIELD_DESCRIPTOR)
                field(BOOL2_FIELD_DESCRIPTOR)
            }

            fun deserialize(deserializer: Deserializer): Nested {
                val struct = deserializer.deserializeStruct(OBJ_DESCRIPTOR)
                val nested = Nested()
                loop@ while (true) {
                    when (struct.findNextFieldIndex()) {
                        NESTED2_FIELD_DESCRIPTOR.index ->
                            nested.nested2 =
                                Nested2.deserialize(
                                    deserializer
                                )
                        BOOL2_FIELD_DESCRIPTOR.index -> nested.bool2 = deserializer.deserializeBoolean()
                        null -> break@loop
                        else -> throw RuntimeException("unexpected field during test")
                    }
                }
                return nested
            }
        }
    }

    class KitchenSinkTest {
        var intField: Int? = null
        var longField: Long? = null
        var shortField: Short? = null
        var boolField: Boolean? = null
        var strField: String? = null
        var listField: List<Int>? = null
        var doubleField: Double? = null
        var nestedField: Nested? = null
        var floatField: Float? = null
        var mapField: Map<String, String>? = null

        companion object {
            val INT_FIELD_DESCRIPTOR = SdkFieldDescriptor.fromSerialName("int", SerialKind.Integer)
            val LONG_FIELD_DESCRIPTOR = SdkFieldDescriptor.fromSerialName("long", SerialKind.Long)
            val SHORT_FIELD_DESCRIPTOR = SdkFieldDescriptor.fromSerialName("short", SerialKind.Short)
            val BOOL_FIELD_DESCRIPTOR = SdkFieldDescriptor.fromSerialName("bool", SerialKind.Boolean)
            val STR_FIELD_DESCRIPTOR = SdkFieldDescriptor.fromSerialName("str", SerialKind.String)
            val LIST_FIELD_DESCRIPTOR = SdkFieldDescriptor.fromSerialName("list", SerialKind.List)
            val DOUBLE_FIELD_DESCRIPTOR = SdkFieldDescriptor.fromSerialName("double", SerialKind.Double)
            val NESTED_FIELD_DESCRIPTOR = SdkFieldDescriptor.fromSerialName("nested", SerialKind.Struct)
            val FLOAT_FIELD_DESCRIPTOR = SdkFieldDescriptor.fromSerialName("float", SerialKind.Float)
            val MAP_FIELD_DESCRIPTOR = SdkFieldDescriptor.fromSerialName("map", SerialKind.Map)

            val OBJ_DESCRIPTOR = SdkObjectDescriptor.build() {
                field(INT_FIELD_DESCRIPTOR)
                field(LONG_FIELD_DESCRIPTOR)
                field(SHORT_FIELD_DESCRIPTOR)
                field(BOOL_FIELD_DESCRIPTOR)
                field(STR_FIELD_DESCRIPTOR)
                field(LIST_FIELD_DESCRIPTOR)
                field(DOUBLE_FIELD_DESCRIPTOR)
                field(NESTED_FIELD_DESCRIPTOR)
                field(FLOAT_FIELD_DESCRIPTOR)
                field(MAP_FIELD_DESCRIPTOR)
            }
        }
    }

    @Test
    fun itHandlesKitchenSink() {
        val payload = """
        {
            "int": 1,
            "long": 2,
            "short": 3,
            "bool": false,
            "str": "a string",
            "list": [10, 11, 12],
            "double": 7.5,
            "nested": {
                "nested2": {
                    "list2": ["x", "y"],
                    "unknown": {
                        "a": "a",
                        "b": "b",
                        "c": ["d", "e", "f"],
                        "g": {
                            "h": "h",
                            "i": "i"
                        }
                     },
                    "int2": 4
                },
                "bool2": true
            },
            "float": 0.2,
            "map": {
                "key1": "value1",
                "key2": "value2"
            }
        }
        """.trimIndent().encodeToByteArray()

        val deserializer = JsonDeserializer(payload)
        val struct = deserializer.deserializeStruct(KitchenSinkTest.OBJ_DESCRIPTOR)
        val sink = KitchenSinkTest()
        loop@ while (true) {
            when (struct.findNextFieldIndex()) {
                KitchenSinkTest.INT_FIELD_DESCRIPTOR.index -> sink.intField = struct.deserializeInt()
                KitchenSinkTest.LONG_FIELD_DESCRIPTOR.index -> sink.longField = struct.deserializeLong()
                KitchenSinkTest.SHORT_FIELD_DESCRIPTOR.index -> sink.shortField = struct.deserializeShort()
                KitchenSinkTest.BOOL_FIELD_DESCRIPTOR.index -> sink.boolField = struct.deserializeBoolean()
                KitchenSinkTest.STR_FIELD_DESCRIPTOR.index -> sink.strField = struct.deserializeString()
                KitchenSinkTest.LIST_FIELD_DESCRIPTOR.index -> sink.listField = deserializer.deserializeList(KitchenSinkTest.LIST_FIELD_DESCRIPTOR) {
                    val list = mutableListOf<Int>()
                    while (hasNextElement()) {
                        list.add(deserializeInt())
                    }
                    return@deserializeList list
                }
                KitchenSinkTest.DOUBLE_FIELD_DESCRIPTOR.index -> sink.doubleField = struct.deserializeDouble()
                KitchenSinkTest.NESTED_FIELD_DESCRIPTOR.index ->
                    sink.nestedField =
                        Nested.deserialize(
                            deserializer
                        )
                KitchenSinkTest.FLOAT_FIELD_DESCRIPTOR.index -> sink.floatField = struct.deserializeFloat()
                KitchenSinkTest.MAP_FIELD_DESCRIPTOR.index -> sink.mapField = deserializer.deserializeMap(KitchenSinkTest.MAP_FIELD_DESCRIPTOR) {
                    val map = mutableMapOf<String, String>()
                    while (hasNextEntry()) {
                        map[key()] = deserializeString()
                    }
                    return@deserializeMap map
                }
                null -> break@loop
                else -> throw RuntimeException("unexpected field during test")
            }
        }

        assertEquals(1, sink.intField)
        assertEquals(2L, sink.longField)
        assertEquals(3.toShort(), sink.shortField)
        assertEquals(false, sink.boolField)
        assertEquals("a string", sink.strField)
        sink.listField.shouldContainExactly(listOf(10, 11, 12))
        assertTrue(abs(sink.doubleField!! - 7.5) <= 0.0001)

        assertEquals(sink.nestedField!!.nested2!!.int2, 4)
        sink.nestedField!!.nested2!!.list2.shouldContainExactly(listOf("x", "y"))
        assertEquals(sink.nestedField!!.bool2, true)

        assertTrue(abs(sink.floatField!! - 0.2f) <= 0.0001f)
        val expectedMap = mapOf("key1" to "value1", "key2" to "value2")
        sink.mapField!!.shouldContainExactly(expectedMap)
    }

    @Test
    fun itSkipsExplicitNulls() {
        val payload = """
         {
             "x": 1,
             "y": null,
             "z": 2
         }
        """.trimIndent().encodeToByteArray()

        val deserializer = JsonDeserializer(payload)
        var x: Int? = null
        var y: Int? = null
        var z: Int? = null
        val X_DESCRIPTOR = SdkFieldDescriptor.fromSerialName("x", SerialKind.Integer)
        val Y_DESCRIPTOR = SdkFieldDescriptor.fromSerialName("y", SerialKind.Integer)
        val Z_DESCRIPTOR = SdkFieldDescriptor.fromSerialName("z", SerialKind.Integer)
        val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
            field(X_DESCRIPTOR)
            field(Y_DESCRIPTOR)
            field(Z_DESCRIPTOR)
        }

        deserializer.deserializeStruct(OBJ_DESCRIPTOR) {
            loop@ while (true) {
                when (findNextFieldIndex()) {
                    X_DESCRIPTOR.index -> x = deserializeInt()
                    Y_DESCRIPTOR.index -> {
                        fail("field y should not have been enumerated")
                    }
                    Z_DESCRIPTOR.index -> z = deserializeInt()
                    null -> break@loop
                }
            }
        }
        assertEquals(1, x)
        assertNull(y)
        assertEquals(2, z)
    }
}
