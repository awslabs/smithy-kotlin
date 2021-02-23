/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package software.amazon.smithy.kotlin.codegen

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldContainOnlyOnce
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.smithy.codegen.core.CodegenException
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.StringShape
import software.amazon.smithy.model.traits.EnumDefinition
import software.amazon.smithy.model.traits.EnumTrait

class EnumGeneratorTest {

    @Test
    fun `it generates unnamed enums`() {
        val model = """
            namespace com.test
            
            @enum([
                {
                    value: "FOO"
                },
                {
                    value: "BAR",
                    documentation: "Documentation for bar"
                }
            ])
            @documentation("Documentation for this enum")
            string Baz
            
        """.asSmithyModel()

        val provider = KotlinCodegenPlugin.createSymbolProvider(model, "test", "Baz")
        val shape = model.expectShape<StringShape>("com.test#Baz")
        val symbol = provider.toSymbol(shape)
        val writer = KotlinWriter("com.test")
        EnumGenerator(shape, symbol, writer).render()
        val contents = writer.toString()

        val expectedEnumDecl = """
/**
 * Documentation for this enum
 */
sealed class Baz {

    abstract val value: kotlin.String

    /**
     * Documentation for bar
     */
    object Bar : test.model.Baz() {
        override val value: kotlin.String = "BAR"
        override fun toString(): kotlin.String = value
    }

    object Foo : test.model.Baz() {
        override val value: kotlin.String = "FOO"
        override fun toString(): kotlin.String = value
    }

    data class SdkUnknown(override val value: kotlin.String) : test.model.Baz() {
        override fun toString(): kotlin.String = value
    }

    companion object {
        /**
         * Convert a raw value to one of the sealed variants or [SdkUnknown]
         */
        fun fromValue(str: kotlin.String): test.model.Baz = when(str) {
            "BAR" -> Bar
            "FOO" -> Foo
            else -> SdkUnknown(str)
        }

        /**
         * Get a list of all possible variants
         */
        fun values(): List<test.model.Baz> = listOf(
            Bar,
            Foo
        )
    }
}
"""

        contents.shouldContainOnlyOnce(expectedEnumDecl)
    }

    @Test
    fun `it generates named enums`() {
        val t2MicroDoc = "T2 instances are Burstable Performance\n" +
            "Instances that provide a baseline level of CPU\n" +
            "performance with the ability to burst above the\n" +
            "baseline."

        val model = """
            namespace com.test
            
            @enum([
                {
                    value: "t2.nano",
                    name: "T2_NANO"
                },
                {
                    value: "t2.micro",
                    name: "T2_MICRO",
                    documentation: "$t2MicroDoc"
                }
            ])
            @documentation("Documentation for this enum")
            string Baz
            
        """.asSmithyModel()

        val provider = KotlinCodegenPlugin.createSymbolProvider(model, "test", "Baz")
        val shape = model.expectShape<StringShape>("com.test#Baz")
        val symbol = provider.toSymbol(shape)
        val writer = KotlinWriter("com.test")
        EnumGenerator(shape, symbol, writer).render()
        val contents = writer.toString()

        val expectedEnumDecl = """
/**
 * Documentation for this enum
 */
sealed class Baz {

    abstract val value: kotlin.String

    /**
     * T2 instances are Burstable Performance
     * Instances that provide a baseline level of CPU
     * performance with the ability to burst above the
     * baseline.
     */
    object T2Micro : test.model.Baz() {
        override val value: kotlin.String = "t2.micro"
        override fun toString(): kotlin.String = value
    }

    object T2Nano : test.model.Baz() {
        override val value: kotlin.String = "t2.nano"
        override fun toString(): kotlin.String = value
    }

    data class SdkUnknown(override val value: kotlin.String) : test.model.Baz() {
        override fun toString(): kotlin.String = value
    }

    companion object {
        /**
         * Convert a raw value to one of the sealed variants or [SdkUnknown]
         */
        fun fromValue(str: kotlin.String): test.model.Baz = when(str) {
            "t2.micro" -> T2Micro
            "t2.nano" -> T2Nano
            else -> SdkUnknown(str)
        }

        /**
         * Get a list of all possible variants
         */
        fun values(): List<test.model.Baz> = listOf(
            T2Micro,
            T2Nano
        )
    }
}
"""
        contents.shouldContainOnlyOnce(expectedEnumDecl)
    }

    @Test
    fun `it prefixes invalid kotlin identifier names`() {
        // (smithy) enum names are required to start with an ascii letter or underscore, values are not.
        // when an enum is unnamed we convert the value to the name. This test ensures those names are escaped
        // appropriately when the value itself would constitute an invalid kotlin identifier name

        val trait = EnumTrait.builder()
            .addEnum(EnumDefinition.builder().value("0").build())
            .addEnum(EnumDefinition.builder().value("foo").build())
            .build()
        /*
        @enum([
            {
                value: "0"
            },
            {
                value: "foo"
            }
        ])
        string Baz
        */

        val shape = StringShape.builder()
            .id("com.test#Baz")
            .addTrait(trait)
            .build()

        val model = Model.assembler()
            .addShapes(shape)
            .assemble()
            .unwrap()

        val provider = KotlinCodegenPlugin.createSymbolProvider(model, "test", "Baz")
        val symbol = provider.toSymbol(shape)
        val writer = KotlinWriter("com.test")
        EnumGenerator(shape, symbol, writer).render()
        val contents = writer.toString()

        contents.shouldContainOnlyOnce("object _0 : test.model.Baz()")
    }

    @Test
    fun `it fails if prefixing causes a conflict`() {
        // names and values are required to be unique, prefixing invalid identifiers with '_' could potentially
        // (albeit unlikely) cause a conflict with an existing name

        val trait = EnumTrait.builder()
            .addEnum(EnumDefinition.builder().value("0").build())
            .addEnum(EnumDefinition.builder().value("_0").build())
            .build()
        /*
        @enum([
            {
                value: "0"
            },
            {
                value: "_0"
            }
        ])
        string Baz
        */

        val shape = StringShape.builder()
            .id("com.test#Baz")
            .addTrait(trait)
            .build()

        val model = Model.assembler()
            .addShapes(shape)
            .assemble()
            .unwrap()

        val provider = KotlinCodegenPlugin.createSymbolProvider(model, "test", "Baz")
        val symbol = provider.toSymbol(shape)
        val writer = KotlinWriter("com.test")
        val ex = assertThrows<CodegenException> {
            EnumGenerator(shape, symbol, writer).render()
        }

        ex.message!!.shouldContain("prefixing invalid enum value to form a valid Kotlin identifier causes generated sealed class names to not be unique")
    }
}
