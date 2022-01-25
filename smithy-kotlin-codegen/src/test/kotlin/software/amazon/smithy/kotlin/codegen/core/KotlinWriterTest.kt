/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.amazon.smithy.kotlin.codegen.core

import io.kotest.matchers.string.shouldNotContain
import software.amazon.smithy.kotlin.codegen.integration.SectionId
import software.amazon.smithy.kotlin.codegen.model.buildSymbol
import software.amazon.smithy.kotlin.codegen.test.TestModelDefault
import software.amazon.smithy.kotlin.codegen.test.shouldContainOnlyOnceWithDiff
import software.amazon.smithy.kotlin.codegen.test.shouldContainWithDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinWriterTest {

    @Test
    fun `writes doc strings`() {
        val writer = KotlinWriter(TestModelDefault.NAMESPACE)
        writer.dokka("These are the docs.\nMore.")
        val result = writer.toString()
        assertTrue(result.contains("/**\n * These are the docs.\n * More.\n */\n"))
    }

    @Test
    fun `escapes $ in doc strings`() {
        val writer = KotlinWriter(TestModelDefault.NAMESPACE)
        val docs = "This is $ valid documentation."
        writer.dokka(docs)
        val result = writer.toString()
        assertTrue(result.contains("/**\n * " + docs + "\n */\n"))
    }

    /**
     * This is \*\/ valid documentation.
     */
    @Test
    fun `escapes comment tokens in doc strings`() {
        val writer = KotlinWriter(TestModelDefault.NAMESPACE)
        val docs = "This is */ valid /* documentation."
        writer.dokka(docs)
        val actual = writer.toString()
        val expected = "This is *&#47; valid &#47;* documentation."
        actual.shouldContainOnlyOnceWithDiff(expected)
    }

    @Test
    fun `it strips html tags from doc strings`() {
        val unit = KotlinWriter(TestModelDefault.NAMESPACE)
        val docs = "<p>here is <b>some</b> sweet <i>sweet</i> <a>html</a></p>"
        unit.dokka(docs)
        val actual = unit.toString()
        actual.shouldContainOnlyOnceWithDiff("here is some sweet sweet html")
    }

    @Test
    fun `it disambiguates type names`() {
        val unit = KotlinWriter(TestModelDefault.NAMESPACE)

        val depASymbol = buildSymbol {
            name = "Foo"
            namespace = TestModelDefault.NAMESPACE + ".a"
            definitionFile = "Foo.kt"
            declarationFile = "Foo.kt"
        }

        unit.addImport(depASymbol)
        unit.write("// Symbol A: #T", depASymbol)

        val depBSymbol = buildSymbol {
            name = "Foo"
            namespace = "software.aws.clientrt.b"
            definitionFile = "Foo.kt"
            declarationFile = "Foo.kt"
        }

        unit.addImport(depBSymbol)
        unit.write("// Symbol B: #T", depBSymbol)

        val expected = """
            // Code generated by smithy-kotlin-codegen. DO NOT EDIT!

            package com.test

            import com.test.a.Foo
            
            // Symbol A: Foo
            // Symbol B: software.aws.clientrt.b.Foo

        """.trimIndent()

        val actual = unit.toString()

        assertEquals(expected, actual)
    }

    @Test
    fun `it disambiguates type names order in imports before write`() {
        val unit = KotlinWriter(TestModelDefault.NAMESPACE)

        val depASymbol = buildSymbol {
            name = "Foo"
            namespace = TestModelDefault.NAMESPACE + ".a"
            definitionFile = "Foo.kt"
            declarationFile = "Foo.kt"
        }
        val depBSymbol = buildSymbol {
            name = "Foo"
            namespace = "software.aws.clientrt.b"
            definitionFile = "Foo.kt"
            declarationFile = "Foo.kt"
        }

        unit.addImport(depASymbol)
        unit.addImport(depBSymbol)

        unit.write("// Symbol A: #T", depASymbol)
        unit.write("// Symbol B: #T", depBSymbol)

        val expected = """
            // Code generated by smithy-kotlin-codegen. DO NOT EDIT!

            package com.test

            import com.test.a.Foo
            
            // Symbol A: Foo
            // Symbol B: software.aws.clientrt.b.Foo

        """.trimIndent()

        val actual = unit.toString()

        assertEquals(expected, actual)
    }

    object TestId : SectionId {
        const val a = "a"
    }

    @Test
    fun `it handles overriding stateful sections`() {
        val unit = KotlinWriter(TestModelDefault.NAMESPACE)

        unit.registerSectionWriter(TestId) { writer, previousValue ->
            val state = writer.getContext(TestId.a)
            writer.write(previousValue)
            writer.write("// section with state $state")
        }

        unit.write("// before section")
        unit.declareSection(TestId, mapOf(TestId.a to 1)) {
            unit.write("// original in section")
        }
        unit.write("// after section")

        val expected = """
            // before section
            // original in section
            // section with state 1
            // after section
        """.trimIndent()
        val actual = unit.toString()

        actual.shouldContainOnlyOnceWithDiff(expected)
    }

    object NestedTestId : SectionId {
        const val a = "a" // intentionally collides with [TestId]
    }

    @Test
    fun `it handles nested stateful sections`() {
        val unit = KotlinWriter(TestModelDefault.NAMESPACE)

        unit.registerSectionWriter(TestId) { writer, previousValue ->
            val state = writer.getContext(TestId.a)
            writer.write("// section with state $state")
            writer.write(previousValue)
        }

        unit.registerSectionWriter(NestedTestId) { writer, _ ->
            val state = writer.getContext(NestedTestId.a)
            writer.write("// nested section with state $state")
        }

        unit.write("// before section")
        unit.declareSection(TestId, mapOf(TestId.a to 1)) {
            unit.declareSection(NestedTestId, mapOf(NestedTestId.a to 2)) {
                unit.write("// original in nested section")
            }
        }
        unit.write("// after section")

        val expected = """
            // before section
            // section with state 1
            // nested section with state 2
            // after section
        """.trimIndent()
        val actual = unit.toString()

        actual.shouldContainOnlyOnceWithDiff(expected)
    }

    @Test
    fun `it only adds redundant dependencies once`() {
        val unit = KotlinWriter(TestModelDefault.NAMESPACE)

        // Do all these things twice
        val symbol = buildSymbol {
            dependency(KotlinDependency.KOTLIN_TEST)
            dependency(KotlinDependency.KOTLIN_TEST)
        }
        unit.addImport(symbol, "Foo")
        unit.addImport(symbol, "Foo")
        unit.dependencies.addAll(KotlinDependency.KOTLIN_TEST.dependencies)
        unit.dependencies.addAll(KotlinDependency.KOTLIN_TEST.dependencies)

        val expected = setOf(KotlinDependency.KOTLIN_TEST.dependencies.first())
        assertEquals(expected, unit.dependencies)
    }

    @Test
    fun itAutoImportsFormattedSymbols() {
        val unit = KotlinWriter("com.test")

        val samePackageSymbol = buildSymbol {
            name = "Foo"
            namespace = "com.test"
        }

        val differentNamespace = buildSymbol {
            name = "Bar"
            namespace = "com.test.subpkg"
        }

        unit.write("val foo = #T()", samePackageSymbol)
        unit.write("val bar = #T()", differentNamespace)

        val contents = unit.toString()
        contents.shouldNotContain("import com.test.Foo")
        contents.shouldContainWithDiff("import com.test.subpkg.Bar")
    }
}
