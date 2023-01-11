/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.kotlin.codegen.rendering

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.kotlin.codegen.core.*
import software.amazon.smithy.kotlin.codegen.integration.SectionId
import software.amazon.smithy.kotlin.codegen.integration.SectionKey
import software.amazon.smithy.kotlin.codegen.model.hasStreamingMember
import software.amazon.smithy.kotlin.codegen.model.operationSignature
import software.amazon.smithy.model.knowledge.OperationIndex
import software.amazon.smithy.model.knowledge.TopDownIndex
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ServiceShape

/**
 * Renders just the service client interfaces. The actual implementation is handled by protocol generators, see
 * [software.amazon.smithy.kotlin.codegen.rendering.protocol.HttpBindingProtocolGenerator].
 */
class ServiceClientGenerator(private val ctx: RenderingContext<ServiceShape>) {
    object Sections {

        /**
         * SectionId used when rendering the service client interface
         */
        object ServiceInterface : SectionId {
            /**
             * The current rendering context for the service generator
             */
            val RenderingContext: SectionKey<RenderingContext<ServiceShape>> = SectionKey("RenderingContext")
        }

        /**
         * SectionId used when rendering the service client builder
         */
        object ServiceBuilder : SectionId {
            /**
             * The current rendering context for the service generator
             */
            val RenderingContext: SectionKey<RenderingContext<ServiceShape>> = SectionKey("RenderingContext")
        }

        /**
         * SectionId used when rendering the service interface companion object
         */
        object CompanionObject : SectionId {
            /**
             * Context key for the service symbol
             */
            val ServiceSymbol: SectionKey<Symbol> = SectionKey("ServiceSymbol")
        }

        /**
         * SectionId used when rendering the service configuration object
         */
        object ServiceConfig : SectionId {
            /**
             * The current rendering context for the service generator
             */
            val RenderingContext: SectionKey<RenderingContext<ServiceShape>> = SectionKey("RenderingContext")
        }
    }

    init {
        require(ctx.shape is ServiceShape) { "ServiceShape is required for generating a service interface; was: ${ctx.shape}" }
    }

    private val service: ServiceShape =
        requireNotNull(ctx.shape) { "ServiceShape is required to render a service client" }
    private val serviceSymbol = ctx.symbolProvider.toSymbol(service)
    private val writer = ctx.writer

    fun render() {
        writer.putContext("service.name", ctx.settings.sdkId)

        val topDownIndex = TopDownIndex.of(ctx.model)
        val operations = topDownIndex.getContainedOperations(service).sortedBy { it.defaultName() }
        val operationsIndex = OperationIndex.of(ctx.model)

        writer.renderDocumentation(service)
        writer.renderAnnotations(service)
        writer.declareSection(Sections.ServiceInterface) {
            write("public interface ${serviceSymbol.name} : #T {", RuntimeTypes.Core.Client.SdkClient)
        }
            .indent()
            .call { overrideServiceName() }
            .call {
                // allow access to client's Config
                writer.dokka("${serviceSymbol.name}'s configuration")
                writer.write("public override val config: Config")
            }
            .call {
                // allow integrations to add additional fields to companion object or configuration
                writer.write("")
                writer.declareSection(
                    Sections.CompanionObject,
                    context = mapOf(Sections.CompanionObject.ServiceSymbol to serviceSymbol),
                ) {
                    renderCompanionObject()
                }
                writer.write("")
                renderServiceBuilder()

                writer.write("")
                renderServiceConfig()
            }
            .call {
                operations.forEach { renderOperation(operationsIndex, it) }
            }
            .dedent()
            .write("}")
            .write("")

        operations.forEach { renderOperationDslOverload(operationsIndex, it) }
    }

    private fun renderServiceConfig() {
        writer.declareSection(
            Sections.ServiceConfig,
            context = mapOf(Sections.ServiceConfig.RenderingContext to ctx),
        ) {
            ClientConfigGenerator(ctx).render()
        }
    }

    private fun renderServiceBuilder() {
        // don't generate a builder if there is no default client to instantiate
        if (ctx.protocolGenerator == null) return

        writer.declareSection(
            Sections.ServiceBuilder,
            context = mapOf(Sections.ServiceBuilder.RenderingContext to ctx),
        ) {
            writer.withBlock(
                "public class Builder internal constructor(): #T<Config, Config.Builder, #T>() {",
                "}",
                RuntimeTypes.Core.Client.AbstractSdkClientBuilder,
                serviceSymbol,
            ) {
                write("override val config: Config.Builder = Config.Builder()")
                write("override fun newClient(config: Config): #T = Default${serviceSymbol.name}(config)", serviceSymbol)
            }
        }
    }

    /**
     * Render the service interface companion object which is the main entry point for most consumers
     *
     * e.g.
     * ```
     * companion object : SdkClientFactory<Config, Config.Builder, LambdaClient, Builder> {
     *     override fun builder: Builder = Builder()
     * }
     * ```
     */
    private fun renderCompanionObject() {
        // don't render a companion object which is used for building a service client unless we have a protocol generator
        if (ctx.protocolGenerator == null) return
        writer.withBlock(
            "public companion object : #T<Config, Config.Builder, #T, Builder> {",
            "}",
            RuntimeTypes.Core.Client.SdkClientFactory,
            serviceSymbol,
        ) {
            write("override fun builder(): Builder = Builder()")
        }
    }

    private fun overrideServiceName() {
        writer.write("")
            .write("override val serviceName: String")
            .indent()
            .write("get() = #service.name:S")
            .dedent()
    }

    private fun renderOperation(opIndex: OperationIndex, op: OperationShape) {
        writer.write("")
        writer.renderDocumentation(op)
        writer.renderAnnotations(op)

        val signature = opIndex.operationSignature(ctx.model, ctx.symbolProvider, op, includeOptionalDefault = true)
        writer.write("public #L", signature)
    }

    private fun renderOperationDslOverload(opIndex: OperationIndex, op: OperationShape) {
        // Add DSL overload (if appropriate)
        opIndex.getInput(op).ifPresent { inputShape ->
            opIndex.getOutput(op).ifPresent { outputShape ->
                val hasOutputStream = outputShape.hasStreamingMember(ctx.model)

                if (!hasOutputStream) {
                    val inputSymbol = ctx.symbolProvider.toSymbol(inputShape)
                    val outputSymbol = ctx.symbolProvider.toSymbol(outputShape)
                    val operationName = op.defaultName()

                    writer.write("")
                    writer.renderDocumentation(op)
                    writer.renderAnnotations(op)
                    writer.write(
                        "public suspend inline fun #T.#L(crossinline block: #T.Builder.() -> Unit): #T = #L(#T.Builder().apply(block).build())",
                        serviceSymbol,
                        operationName,
                        inputSymbol,
                        outputSymbol,
                        operationName,
                        inputSymbol,
                    )
                }
            }
        }
    }
}
