/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.kotlin.codegen.rendering

import software.amazon.smithy.codegen.core.CodegenException
import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.codegen.core.SymbolReference
import software.amazon.smithy.kotlin.codegen.KotlinSettings
import software.amazon.smithy.kotlin.codegen.core.CodegenContext
import software.amazon.smithy.kotlin.codegen.core.ExternalTypes
import software.amazon.smithy.kotlin.codegen.core.KotlinDelegator
import software.amazon.smithy.kotlin.codegen.core.KotlinWriter
import software.amazon.smithy.kotlin.codegen.core.defaultName
import software.amazon.smithy.kotlin.codegen.core.withBlock
import software.amazon.smithy.kotlin.codegen.integration.KotlinIntegration
import software.amazon.smithy.kotlin.codegen.lang.KotlinTypes
import software.amazon.smithy.kotlin.codegen.model.SymbolProperty
import software.amazon.smithy.kotlin.codegen.model.expectShape
import software.amazon.smithy.kotlin.codegen.model.hasAllOptionalMembers
import software.amazon.smithy.kotlin.codegen.model.hasTrait
import software.amazon.smithy.kotlin.codegen.model.traits.PaginationTruncationMember
import software.amazon.smithy.kotlin.codegen.utils.getOrNull
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.knowledge.PaginatedIndex
import software.amazon.smithy.model.knowledge.PaginationInfo
import software.amazon.smithy.model.knowledge.TopDownIndex
import software.amazon.smithy.model.shapes.CollectionShape
import software.amazon.smithy.model.shapes.MapShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.traits.PaginatedTrait

/**
 * Generate paginators for supporting operations.  See
 * https://awslabs.github.io/smithy/1.0/spec/core/behavior-traits.html#paginated-trait for details.
 */
class PaginatorGenerator : KotlinIntegration {
    override fun enabledForService(model: Model, settings: KotlinSettings): Boolean =
        TopDownIndex.of(model)
            .getContainedOperations(settings.service)
            .any { it.hasTrait<PaginatedTrait>() }

    override fun writeAdditionalFiles(ctx: CodegenContext, delegator: KotlinDelegator) {
        val service = ctx.model.expectShape<ServiceShape>(ctx.settings.service)
        val paginatedIndex = PaginatedIndex.of(ctx.model)

        delegator.useFileWriter("Paginators.kt", "${ctx.settings.pkg.name}.paginators") { writer ->
            val paginatedOperations = service.allOperations
                .map { ctx.model.expectShape<OperationShape>(it) }
                .filter { operationShape -> operationShape.hasTrait(PaginatedTrait.ID) }

            paginatedOperations.forEach { paginatedOperation ->
                val paginationInfo = paginatedIndex.getPaginationInfo(service, paginatedOperation).getOrNull()
                    ?: throw CodegenException("Unexpectedly unable to get PaginationInfo from $service $paginatedOperation")
                val paginationItemInfo = getItemDescriptorOrNull(paginationInfo, ctx)

                renderPaginatorForOperation(ctx, writer, paginatedOperation, paginationInfo, paginationItemInfo)
            }
        }
    }

    // Render paginator(s) for operation
    private fun renderPaginatorForOperation(
        ctx: CodegenContext,
        writer: KotlinWriter,
        paginatedOperation: OperationShape,
        paginationInfo: PaginationInfo,
        itemDesc: ItemDescriptor?,
    ) {
        renderResponsePaginator(
            ctx,
            writer,
            paginatedOperation,
            paginationInfo,
        )

        // Optionally generate paginator when nested item is specified on the trait.
        if (itemDesc != null) {
            renderItemPaginator(
                ctx,
                writer,
                paginatedOperation,
                itemDesc,
            )
        }
    }

    // Generate the paginator that iterates over responses
    private fun renderResponsePaginator(
        ctx: CodegenContext,
        writer: KotlinWriter,
        operationShape: OperationShape,
        paginationInfo: PaginationInfo,
    ) {
        val service = ctx.model.expectShape<ServiceShape>(ctx.settings.service)
        val serviceSymbol = ctx.symbolProvider.toSymbol(service)
        val outputShape = paginationInfo.output
        val outputSymbol = ctx.symbolProvider.toSymbol(outputShape)
        val inputSymbol = ctx.symbolProvider.toSymbol(paginationInfo.input)
        val cursorSymbol = ctx.symbolProvider.toSymbol(paginationInfo.inputTokenMember)

        val nextMarkerLiteral = paginationInfo.outputTokenMemberPath.joinToString(separator = "?.") {
            it.defaultName()
        }
        val markerLiteral = paginationInfo.inputTokenMember.defaultName()

        val docBody = """
            Paginate over [${outputSymbol.name}] results.
            
            When this operation is called, a [kotlinx.coroutines.Flow] is created. Flows are lazy (cold) so no service
            calls are made until the flow is collected. This also means there is no guarantee that the request is valid
            until then. Once you start collecting the flow, the SDK will lazily load response pages by making service
            calls until there are no pages left or the flow is cancelled. If there are errors in your request, you will
            see the failures only after you start collection.
        """.trimIndent()
        val docReturn = "@return A [kotlinx.coroutines.flow.Flow] that can collect [${outputSymbol.name}]"

        val inputParameter = if (paginationInfo.input.hasAllOptionalMembers) {
            writer.format("initialRequest: #1T = #1T { }", inputSymbol)
        } else {
            writer.format("initialRequest: #T", inputSymbol)
        }

        writer.write("")
        writer
            .dokka(
                """
                    |$docBody
                    |@param initialRequest A [${inputSymbol.name}] to start pagination
                    |$docReturn
                """.trimMargin(),
            )
            .addImportReferences(cursorSymbol, SymbolReference.ContextOption.DECLARE)
            .withBlock(
                "#L fun #T.#LPaginated(#L): #T<#T> =",
                "",
                ctx.settings.api.visibility,
                serviceSymbol,
                operationShape.defaultName(),
                inputParameter,
                ExternalTypes.KotlinxCoroutines.Flow,
                outputSymbol,
            ) {
                withBlock("#T {", "}", ExternalTypes.KotlinxCoroutines.FlowGenerator) {
                    write("var cursor: #F = null", cursorSymbol)
                    write("var hasNextPage: Boolean = true")
                    write("")
                    withBlock("while (hasNextPage) {", "}") {
                        withBlock("val req = initialRequest.copy {", "}") {
                            write("this.$markerLiteral = cursor")
                        }
                        write(
                            "val result = this@#1LPaginated.#1L(req)",
                            operationShape.defaultName(),
                        )
                        write("cursor = result.$nextMarkerLiteral")

                        val hasNextPageFlag = outputShape
                            .members()
                            .singleOrNull { it.hasTrait(PaginationTruncationMember.ID) }
                            ?.defaultName()
                            ?.let { "result.$it" }
                            ?: "cursor?.isNotEmpty()"

                        write("hasNextPage = #L == true", hasNextPageFlag)
                        write("emit(result)")
                    }
                }
            }

        writer.write("")
        writer
            .dokka(
                """
                    |$docBody
                    |@param block A builder block used for DSL-style invocation of the operation
                    |$docReturn
                """.trimMargin(),
            )
            .withBlock(
                "#L fun #T.#LPaginated(block: #T.Builder.() -> #T): #T<#T> =",
                "",
                ctx.settings.api.visibility,
                serviceSymbol,
                operationShape.defaultName(),
                inputSymbol,
                KotlinTypes.Unit,
                ExternalTypes.KotlinxCoroutines.Flow,
                outputSymbol,
            ) {
                write("#LPaginated(#T.Builder().apply(block).build())", operationShape.defaultName(), inputSymbol)
            }
    }

    // Generate a paginator that iterates over the model-specified item
    private fun renderItemPaginator(
        ctx: CodegenContext,
        writer: KotlinWriter,
        operationShape: OperationShape,
        itemDesc: ItemDescriptor,
    ) {
        val serviceShape = ctx.model.expectShape<ServiceShape>(ctx.settings.service)
        val outputShape = ctx.model.expectShape(operationShape.outputShape)
        val outputSymbol = ctx.symbolProvider.toSymbol(outputShape)

        writer.write("")
        writer.dokka(
            """
                This paginator transforms the flow returned by [${operationShape.defaultName()}Paginated] 
                to access the nested member [${itemDesc.targetMember.defaultName(serviceShape)}]
                @return A [kotlinx.coroutines.flow.Flow] that can collect [${itemDesc.targetMember.defaultName(serviceShape)}]
            """.trimIndent(),
        )
        writer
            .addImport(ExternalTypes.KotlinxCoroutines.FlowTransform)
            .addImport(itemDesc.itemSymbol)
            .addImportReferences(itemDesc.itemSymbol, SymbolReference.ContextOption.USE)
            // @JvmName is required due to Java interop compatibility in the compiler.
            // Multiple functions may have the same name and the generic does not disambiguate the type in Java.
            // NOTE: This does not mean these functions are callable from Java.
            .write(
                """@#T("#L#L")""",
                KotlinTypes.Jvm.JvmName,
                outputSymbol.name.replaceFirstChar(Char::lowercaseChar),
                itemDesc.targetMember.defaultName(serviceShape),
            )
            .withBlock(
                "#L fun #T<#T>.#L(): #T<#L> =",
                "",
                ctx.settings.api.visibility,
                ExternalTypes.KotlinxCoroutines.Flow,
                outputSymbol,
                itemDesc.itemLiteral,
                ExternalTypes.KotlinxCoroutines.Flow,
                itemDesc.collectionLiteral,
            ) {
                withBlock("transform() { response -> ", "}") {
                    withBlock("response.#L?.forEach {", "}", itemDesc.itemPathLiteral) {
                        write("emit(it)")
                    }
                }
            }
    }
}

/**
 * Model info necessary to codegen paginator item
 */
private data class ItemDescriptor(
    val collectionLiteral: String,
    val targetMember: Shape,
    val itemLiteral: String,
    val itemPathLiteral: String,
    val itemSymbol: Symbol,
)

/**
 * Return an [ItemDescriptor] if model supplies, otherwise null
 */
private fun getItemDescriptorOrNull(paginationInfo: PaginationInfo, ctx: CodegenContext): ItemDescriptor? {
    val itemMemberId = paginationInfo.itemsMemberPath?.lastOrNull()?.target ?: return null

    val itemLiteral = paginationInfo.itemsMemberPath!!.last()!!.defaultName()
    val itemPathLiteral = paginationInfo.itemsMemberPath.joinToString(separator = "?.") { it.defaultName() }
    val itemMember = ctx.model.expectShape(itemMemberId)
    val (collectionLiteral, targetMember) = when (itemMember) {
        is MapShape ->
            ctx.symbolProvider.toSymbol(itemMember)
                .expectProperty(SymbolProperty.ENTRY_EXPRESSION) as String to itemMember
        is CollectionShape ->
            ctx.symbolProvider.toSymbol(ctx.model.expectShape(itemMember.member.target)).name to ctx.model.expectShape(
                itemMember.member.target,
            )
        else -> error("Unexpected shape type ${itemMember.type}")
    }

    return ItemDescriptor(
        collectionLiteral,
        targetMember,
        itemLiteral,
        itemPathLiteral,
        ctx.symbolProvider.toSymbol(itemMember),
    )
}
