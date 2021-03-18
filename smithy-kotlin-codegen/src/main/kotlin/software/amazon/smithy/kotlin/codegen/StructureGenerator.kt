/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package software.amazon.smithy.kotlin.codegen

import software.amazon.smithy.codegen.core.CodegenException
import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.codegen.core.SymbolProvider
import software.amazon.smithy.kotlin.codegen.integration.ProtocolGenerator
import software.amazon.smithy.kotlin.codegen.lang.KotlinTypes
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.BlobShape
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.ShapeType
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.model.traits.RetryableTrait
import software.amazon.smithy.model.traits.SensitiveTrait
import software.amazon.smithy.model.traits.StreamingTrait

/**
 * Renders Smithy structure shapes
 */
class StructureGenerator(
    val model: Model,
    private val symbolProvider: SymbolProvider,
    private val writer: KotlinWriter,
    private val shape: StructureShape,
    private val protocolGenerator: ProtocolGenerator? = null
) {

    fun render() {
        if (!shape.hasTrait<ErrorTrait>()) {
            renderStructure()
        } else {
            renderError()
        }
    }

    private val sortedMembers: List<MemberShape> = shape.allMembers.values.sortedBy { symbolProvider.toMemberName(it) }
    private val memberNameSymbolIndex: Map<MemberShape, Pair<String, Symbol>> =
        sortedMembers
            .map { member -> member to Pair(symbolProvider.toMemberName(member), symbolProvider.toSymbol(member)) }
            .toMap()

    /**
     * Renders a normal (non-error) Smithy structure to a Kotlin class
     */
    private fun renderStructure() {
        startGenericStructureBlock("class #class.name:L private constructor(builder: BuilderImpl) {")
        writer.closeBlock("}").write("")

        writer.removeContext("class.name")
    }

    private fun startGenericStructureBlock(start: String) {
        val symbol = symbolProvider.toSymbol(shape)
        // push context to be used throughout generation of the class
        writer.putContext("class.name", symbol.name)

        writer.renderDocumentation(shape)
        // constructor
        writer.openBlock(start)
            .call { renderImmutableProperties() }
            .write("")
            .call { renderCompanionObject() }
            .call { renderToString() }
            .call { renderHashCode() }
            .call { renderEquals() }
            .call { renderCopy() }
            .call { renderJavaBuilderInterface() }
            .call { renderDslBuilderInterface() }
            .call { renderBuilderImpl() }
    }

    private fun renderImmutableProperties() {
        // generate the immutable properties that are set from a builder
        sortedMembers.forEach {
            val (memberName, memberSymbol) = memberNameSymbolIndex[it]!!
            writer.renderMemberDocumentation(model, it)
            if (shape.hasTrait<ErrorTrait>() && "message" == memberName) {
                // TODO: Have to handle the case where "cause" is a property in the Smithy model
                val targetShape = model.getShape(it.target).get()
                if (!targetShape.isStringShape) {
                    throw CodegenException("Message is a reserved name for exception types and cannot be used for any other property")
                }
                // Override Throwable's message property
                writer.write("override val #1L: #2L = builder.#1L!!", memberName, memberSymbol.name)
            } else {
                writer.write("val #1L: #2P = builder.#1L", memberName, memberSymbol)
            }
        }
    }

    private fun renderCompanionObject() {
        writer.withBlock("companion object {", "}") {
            write("@JvmStatic")
            write("fun fluentBuilder(): FluentBuilder = BuilderImpl()")
            write("")
            write("fun builder(): DslBuilder = BuilderImpl()")
            write("")
            write("operator fun invoke(block: DslBuilder.() -> #Q): #class.name:L = BuilderImpl().apply(block).build()", KotlinTypes.Unit)
            write("")
        }
    }

    // generate a `toString()` implementation
    private fun renderToString() {
        writer.write("")
        writer.withBlock("override fun toString(): #Q = buildString {", "}", KotlinTypes.String) {
            write("append(\"#class.name:L(\")")

            when {
                sortedMembers.isEmpty() -> write("append(\")\")")
                else -> {
                    sortedMembers.forEachIndexed { index, memberShape ->
                        val (memberName, _) = memberNameSymbolIndex[memberShape]!!
                        val separator = if (index < sortedMembers.size - 1) "," else ")"

                        val targetShape = model.expectShape(memberShape.target)
                        if (targetShape.hasTrait<SensitiveTrait>()) {
                            write("append(\"#1L=*** Sensitive Data Redacted ***$separator\")", memberName)
                        } else {
                            write("append(\"#1L=\$#1L$separator\")", memberName)
                        }
                    }
                }
            }
        }
    }

    // generate a `hashCode()` implementation
    private fun renderHashCode() {
        writer.write("")
        writer.withBlock("override fun hashCode(): #Q {", "}", KotlinTypes.Int) {
            when {
                sortedMembers.isEmpty() -> write("var result = javaClass.hashCode()")
                else -> {
                    write("var result = #1L#2L", memberNameSymbolIndex[sortedMembers[0]]!!.first, selectHashFunctionForShape(sortedMembers[0]))
                    if (sortedMembers.size > 1) {
                        sortedMembers.drop(1).forEach { memberShape ->
                            write("result = 31 * result + (#1L#2L)", memberNameSymbolIndex[memberShape]!!.first, selectHashFunctionForShape(memberShape))
                        }
                    }
                }
            }
            write("return result")
        }
    }

    // Return the appropriate hashCode fragment based on ShapeID of member target.
    private fun selectHashFunctionForShape(member: MemberShape): String {
        val targetShape = model.expectShape(member.target)
        // also available already in the byMember map
        val targetSymbol = symbolProvider.toSymbol(targetShape)

        return when (targetShape.type) {
            ShapeType.INTEGER ->
                when (targetSymbol.isBoxed) {
                    true -> " ?: 0"
                    else -> ""
                }
            ShapeType.BYTE ->
                when (targetSymbol.isBoxed) {
                    true -> "?.toInt() ?: 0"
                    else -> ".toInt()"
                }
            ShapeType.BLOB ->
                if (targetShape.hasTrait<StreamingTrait>()) {
                    // ByteStream
                    "?.hashCode() ?: 0"
                } else {
                    // ByteArray
                    "?.contentHashCode() ?: 0"
                }
            else ->
                when (targetSymbol.isBoxed) {
                    true -> "?.hashCode() ?: 0"
                    else -> ".hashCode()"
                }
        }
    }

    // generate a `equals()` implementation
    private fun renderEquals() {
        writer.write("")
        writer.withBlock("override fun equals(other: #Q?): #Q {", "}", KotlinTypes.Any, KotlinTypes.Boolean) {
            write("if (this === other) return true")
            write("if (javaClass != other?.javaClass) return false")
            write("")
            write("other as #class.name:L")
            write("")

            for (memberShape in sortedMembers) {
                val target = model.expectShape(memberShape.target)
                val memberName = memberNameSymbolIndex[memberShape]!!.first
                if (target is BlobShape && !target.hasTrait<StreamingTrait>()) {
                    openBlock("if (#1L != null) {", memberName)
                        .write("if (other.#1L == null) return false", memberName)
                        .write("if (!#1L.contentEquals(other.#1L)) return false", memberName)
                        .closeBlock("} else if (other.#1L != null) return false", memberName)
                } else {
                    write("if (#1L != other.#1L) return false", memberName)
                }
            }

            write("")
            write("return true")
        }
    }

    // generate a `copy()` implementation
    private fun renderCopy() {
        if (sortedMembers.isEmpty()) return

        // copy has to go through a builder, if we were to generate a "normal"
        // data class copy() with defaults for all properties we would end up in the same
        // situation we have with constructors and positional arguments not playing well
        // with models evolving over time (e.g. new fields in different positions)
        writer.write("")
            .write("fun copy(block: DslBuilder.() -> #Q = {}): #class.name:L = BuilderImpl(this).apply(block).build()", KotlinTypes.Unit)
            .write("")
    }

    private fun renderJavaBuilderInterface() {
        writer.write("")
            .withBlock("interface FluentBuilder {", "}") {
                write("fun build(): #class.name:L")
                for (member in sortedMembers) {
                    val (memberName, memberSymbol) = memberNameSymbolIndex[member]!!
                    // we want the type names sans nullability (?) for arguments
                    write("fun #1L(#1L: #2T): FluentBuilder", memberName, memberSymbol)
                }
            }
    }

    private fun renderDslBuilderInterface() {
        writer.write("")
            .withBlock("interface DslBuilder {", "}") {
                val structMembers: MutableList<MemberShape> = mutableListOf()

                for (member in sortedMembers) {
                    val (memberName, memberSymbol) = memberNameSymbolIndex[member]!!
                    val targetShape = model.getShape(member.target).get()
                    when {
                        targetShape.isStructureShape -> structMembers.add(member)
                    }

                    write("var #L: #P", memberName, memberSymbol)
                }

                write("")
                write("fun build(): #class.name:L")
                for (member in structMembers) {
                    val (memberName, memberSymbol) = memberNameSymbolIndex[member]!!
                    openBlock("fun #L(block: #L.DslBuilder.() -> #Q) {", memberName, memberSymbol.name, KotlinTypes.Unit)
                        .write("this.#L = #L.invoke(block)", memberName, memberSymbol.name)
                        .closeBlock("}")
                }
            }
    }

    private fun renderBuilderImpl() {
        writer.write("")
            .withBlock("private class BuilderImpl() : FluentBuilder, DslBuilder {", "}") {
                // override DSL properties
                for (member in sortedMembers) {
                    val (memberName, memberSymbol) = memberNameSymbolIndex[member]!!
                    write("override var #L: #D", memberName, memberSymbol)
                }

                write("")

                // generate the constructor that converts from the underlying immutable class to a builder instance
                withBlock("constructor(x: #class.name:L) : this() {", "}") {
                    for (member in sortedMembers) {
                        val (memberName, _) = memberNameSymbolIndex[member]!!
                        write("this.#1L = x.#1L", memberName)
                    }
                }

                // generate the Java builder overrides
                // NOTE: The enum overloads are the same in both the Java and DslBuilder interfaces, generating
                // the Java builder implementation will satisfy the DslInterface w.r.t enum overloads
                write("")
                write("override fun build(): #class.name:L = #class.name:L(this)")
                for (member in sortedMembers) {
                    val (memberName, memberSymbol) = memberNameSymbolIndex[member]!!
                    // we want the type names sans nullability (?) for arguments
                    write("override fun #1L(#1L: #2T): FluentBuilder = apply { this.#1L = #1L }", memberName, memberSymbol)
                }
            }
    }

    /**
     * Renders a Smithy error type to a Kotlin exception type
     */
    private fun renderError() {
        val errorTrait: ErrorTrait = shape.expectTrait()
        val isRetryable = shape.hasTrait<RetryableTrait>()

        val exceptionBaseClass = protocolGenerator?.exceptionBaseClassSymbol ?: ProtocolGenerator.DefaultServiceExceptionSymbol
        writer.addImport(exceptionBaseClass)

        startGenericStructureBlock("class #class.name:L private constructor(builder: BuilderImpl) : ${exceptionBaseClass.name}() {")
        writer.withBlock("", "}") {
            write("")
            if (isRetryable) {
                call { renderRetryable() }
            }
            call { renderErrorType(errorTrait) }
        }
        writer.write("")
        writer.removeContext("class.name")
    }

    private fun renderRetryable() {
        writer.write("")
        writer.write("override val isRetryable = true")
    }

    private fun renderErrorType(errorTrait: ErrorTrait) {
        writer.write("")
        when {
            errorTrait.isClientError -> {
                writer.write("override val errorType = ErrorType.Client")
            }
            errorTrait.isServerError -> {
                writer.write("override val errorType = ErrorType.Server")
            }
            else -> {
                throw CodegenException("Errors must be either of client or server type")
            }
        }
    }
}
