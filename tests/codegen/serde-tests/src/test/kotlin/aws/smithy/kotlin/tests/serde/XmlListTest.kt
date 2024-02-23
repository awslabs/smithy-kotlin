/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.tests.serde

import aws.smithy.kotlin.tests.serde.xml.model.StructType
import aws.smithy.kotlin.tests.serde.xml.serde.deserializeStructTypeDocument
import aws.smithy.kotlin.tests.serde.xml.serde.serializeStructTypeDocument
import kotlin.test.Test

class XmlListTest : AbstractXmlTest() {
    @Test
    fun testNormalList() {
        val expected = StructType {
            normalList = listOf("bar", "baz")
        }
        val payload = """
            <StructType>
                <normalList>
                    <member>bar</member>
                    <member>baz</member>
                </normalList>
            </StructType>
        """.trimIndent()
        testRoundTrip(expected, payload, ::serializeStructTypeDocument, ::deserializeStructTypeDocument)
    }

    @Test
    fun testSparseList() {
        val expected = StructType {
            sparseList = listOf("bar", null, "baz")
        }
        val payload = """
            <StructType>
                <sparseList>
                    <member>bar</member>
                    <member></member>
                    <member>baz</member>
                </sparseList>
            </StructType>
        """.trimIndent()
        testRoundTrip(expected, payload, ::serializeStructTypeDocument, ::deserializeStructTypeDocument)
    }

    @Test
    fun testNestedList() {
        val expected = StructType {
            nestedList = listOf(
                listOf("a", "b", "c"),
                listOf("x", "y", "z"),
            )
        }
        val payload = """
            <StructType>
                <nestedList>
                    <member>
                        <member>a</member>
                        <member>b</member>
                        <member>c</member>
                    </member>
                    <member>
                        <member>x</member>
                        <member>y</member>
                        <member>z</member>
                    </member>
                </nestedList>
            </StructType>
        """.trimIndent()
        testRoundTrip(expected, payload, ::serializeStructTypeDocument, ::deserializeStructTypeDocument)
    }

    @Test
    fun testListWithRenamedMember() {
        val expected = StructType {
            renamedMemberList = listOf("bar", "baz")
        }
        val payload = """
            <StructType>
                <renamedMemberList>
                    <item>bar</item>
                    <item>baz</item>
                </renamedMemberList>
            </StructType>
        """.trimIndent()
        testRoundTrip(expected, payload, ::serializeStructTypeDocument, ::deserializeStructTypeDocument)
    }

    @Test
    fun testFlatList() {
        val expected = StructType {
            flatList = listOf("foo", "bar")
        }
        val payload = """
            <StructType>
                <flatlist1>foo</flatlist1>
                <flatlist1>bar</flatlist1>
            </StructType>
        """.trimIndent()
        testRoundTrip(expected, payload, ::serializeStructTypeDocument, ::deserializeStructTypeDocument)
    }

    // FIXME - re-enable after we implement fix
    // @Test
    // fun testDeserializeInterspersedSparseLists() {
    //     // see https://github.com/awslabs/aws-sdk-kotlin/issues/1220
    //     val expected = StructType {
    //         flatList = listOf("foo", "bar")
    //         secondFlatList = listOf(1, 2)
    //     }
    //     val payload = """
    //         <StructType>
    //             <flatlist1>foo</flatlist1>
    //             <flatlist2>1</flatlist2>
    //             <flatlist1>bar</flatlist1>
    //             <flatlist2>2</flatlist2>
    //         </StructType>
    //     """.trimIndent()
    //     val deserializer = XmlDeserializer(payload.encodeToByteArray())
    //     val actualDeserialized = deserializeStructTypeDocument(deserializer)
    //     assertEquals(expected, actualDeserialized)
    // }
}