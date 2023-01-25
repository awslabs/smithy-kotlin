/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.kotlin.codegen.rendering.protocol

import software.amazon.smithy.aws.traits.HttpChecksumTrait
import software.amazon.smithy.kotlin.codegen.core.*
import software.amazon.smithy.kotlin.codegen.integration.SectionId
import software.amazon.smithy.kotlin.codegen.integration.SectionKey
import software.amazon.smithy.kotlin.codegen.lang.KotlinTypes
import software.amazon.smithy.kotlin.codegen.model.*
import software.amazon.smithy.kotlin.codegen.rendering.serde.deserializerName
import software.amazon.smithy.kotlin.codegen.rendering.serde.serializerName
import software.amazon.smithy.kotlin.codegen.utils.getOrNull
import software.amazon.smithy.model.knowledge.OperationIndex
import software.amazon.smithy.model.knowledge.TopDownIndex
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.traits.EndpointTrait
import software.amazon.smithy.model.traits.HttpChecksumRequiredTrait

/**
 * Renders an implementation of a service interface for HTTP protocol
 */
abstract class HttpProtocolClientGenerator(
    protected val ctx: ProtocolGenerator.GenerationContext,
    protected val middleware: List<ProtocolMiddleware>,
    protected val httpBindingResolver: HttpBindingResolver,
) {

    object OperationDeserializerBinding : SectionId {
        // Context for operation being codegened at the time of section invocation
        val Operation: SectionKey<OperationShape> = SectionKey("Operation")
    }

    /**
     * Render the implementation of the service client interface
     */
    open fun render(writer: KotlinWriter) {
        val symbol = ctx.symbolProvider.toSymbol(ctx.service)
        val topDownIndex = TopDownIndex.of(ctx.model)
        val operations = topDownIndex.getContainedOperations(ctx.service).sortedBy { it.defaultName() }
        val operationsIndex = OperationIndex.of(ctx.model)

        importSymbols(writer)

        writer.openBlock("internal class Default${symbol.name}(override val config: ${symbol.name}.Config) : ${symbol.name} {")
            .call { renderProperties(writer) }
            .write("")
            .call { renderInit(writer) }
            .write("")
            .call {
                // allow middleware to write properties that can be re-used
                val appliedMiddleware = mutableSetOf<ProtocolMiddleware>()
                operations.forEach { op ->
                    middleware.filterTo(appliedMiddleware) { it.isEnabledFor(ctx, op) }
                }

                // render properties from middleware to service client
                appliedMiddleware.forEach { it.renderProperties(writer) }
            }
            .call {
                operations.forEach { op ->
                    renderOperationBody(writer, operationsIndex, op)
                }
            }
            .write("")
            .call { renderClose(writer) }
            .write("")
            .call { renderAdditionalMethods(writer) }
            .closeBlock("}")
            .write("")
    }

    /**
     * Render any properties this class should have.
     */
    protected open fun renderProperties(writer: KotlinWriter) {
        writer.write("private val managedResources = #T()", RuntimeTypes.IO.SdkManagedGroup)
        writer.write("private val client = #T(config.httpClientEngine)", RuntimeTypes.Http.SdkHttpClient)
    }

    protected open fun importSymbols(writer: KotlinWriter) {
        writer.addImport("${ctx.settings.pkg.name}.model", "*")
        writer.addImport("${ctx.settings.pkg.name}.transform", "*")

        val defaultClientSymbols = setOf(
            RuntimeTypes.Http.Operation.SdkHttpOperation,
            RuntimeTypes.Http.Operation.context,
        )
        writer.addImport(defaultClientSymbols)
        writer.dependencies.addAll(KotlinDependency.HTTP.dependencies)
    }

    /**
     * Render the class initialization block.
     */
    protected open fun renderInit(writer: KotlinWriter) {
        writer.withBlock("init {", "}") {
            write("managedResources.#T(config.httpClientEngine)", RuntimeTypes.IO.addIfManaged)
        }
    }

    /**
     * Render the full operation body (signature, setup, execute)
     */
    protected open fun renderOperationBody(writer: KotlinWriter, opIndex: OperationIndex, op: OperationShape) {
        writer.write("")
        writer.renderDocumentation(op)
        writer.renderAnnotations(op)
        val signature = opIndex.operationSignature(ctx.model, ctx.symbolProvider, op)
        writer.openBlock("override #L {", signature)
            .call { renderOperationSetup(writer, opIndex, op) }
            .call { renderOperationMiddleware(op, writer) }
            .call { renderFinalizeBeforeExecute(writer, opIndex, op) }
            .call { renderOperationExecute(writer, opIndex, op) }
            .closeBlock("}")
    }

    /**
     * Renders the operation body up to the point where the call is executed. This function is responsible for setting
     * up the execution context used for this operation
     */
    protected open fun renderOperationSetup(writer: KotlinWriter, opIndex: OperationIndex, op: OperationShape) {
        val inputShape = opIndex.getInput(op)
        val outputShape = opIndex.getOutput(op)
        val httpTrait = httpBindingResolver.httpTrait(op)

        val (inputSymbolName, outputSymbolName) = ioSymbolNames(op)

        writer.openBlock(
            "val op = SdkHttpOperation.build<#L, #L> {",
            "}",
            inputSymbolName,
            outputSymbolName,
        ) {
            if (inputShape.isPresent) {
                writer.write("serializer = ${op.serializerName()}()")
            } else {
                // no serializer implementation is generated for operations with no input, inline the HTTP
                // protocol request from the operation itself
                // NOTE: this will never be triggered for AWS models where we preprocess operations to always have inputs/outputs
                writer.addImport(RuntimeTypes.Http.Request.HttpRequestBuilder)
                writer.addImport(RuntimeTypes.Core.ExecutionContext)
                writer.openBlock("serializer = object : HttpSerialize<#Q> {", "}", KotlinTypes.Unit) {
                    writer.openBlock(
                        "override suspend fun serialize(context: ExecutionContext, input: #Q): HttpRequestBuilder {",
                        "}",
                        KotlinTypes.Unit,
                    ) {
                        writer.write("val builder = HttpRequestBuilder()")
                        writer.write("builder.method = HttpMethod.#L", httpTrait.method.uppercase())
                        // NOTE: since there is no input the URI can only be a literal (no labels to fill)
                        writer.write("builder.url.path = #S", httpTrait.uri.toString())
                        writer.write("return builder")
                    }
                }
            }

            writer.declareSection(OperationDeserializerBinding, mapOf(OperationDeserializerBinding.Operation to op)) {
                if (outputShape.isPresent) {
                    write("deserializer = ${op.deserializerName()}()")
                } else {
                    write("deserializer = UnitDeserializer")
                }
            }

            // execution context
            writer.openBlock("context {", "}") {
                writer.write("expectedHttpStatus = ${httpTrait.code}")
                // property from implementing SdkClient
                writer.write("service = serviceName")
                writer.write("operationName = #S", op.id.name)

                // optional endpoint trait
                op.getTrait<EndpointTrait>()?.let { endpointTrait ->
                    val hostPrefix = endpointTrait.hostPrefix.segments.joinToString(separator = "") { segment ->
                        if (segment.isLabel) {
                            // hostLabel can only target string shapes
                            // see: https://awslabs.github.io/smithy/1.0/spec/core/endpoint-traits.html#hostlabel-trait
                            val member =
                                inputShape.get().members().first { member -> member.memberName == segment.content }
                            "\${input.${member.defaultName()}}"
                        } else {
                            segment.content
                        }
                    }
                    writer.write("hostPrefix = #S", hostPrefix)
                }
            }
        }

        writer.write("op.execution.retryStrategy = config.retryStrategy")
    }

    protected open fun renderFinalizeBeforeExecute(writer: KotlinWriter, opIndex: OperationIndex, op: OperationShape) {
        // add config interceptors last (after all middleware and SDK defaults have had a chance to register)
        writer.write("op.interceptors.addAll(config.interceptors)")
    }

    /**
     * Render the actual execution of a request using the HTTP client
     */
    protected open fun renderOperationExecute(writer: KotlinWriter, opIndex: OperationIndex, op: OperationShape) {
        val inputShape = opIndex.getInput(op)
        val outputShape = opIndex.getOutput(op)
        val hasOutputStream = outputShape.map { it.hasStreamingMember(ctx.model) }.orElse(false)
        val inputVariableName = if (inputShape.isPresent) "input" else KotlinTypes.Unit.fullName

        writer
            .write(
                """val rootSpan = config.tracer.createRootSpan("#L-${'$'}{op.context.#T}")""",
                op.id.name,
                RuntimeTypes.Http.Operation.sdkRequestId,
            )
            .withBlock(
                "return #T.#T(rootSpan) {",
                "}",
                RuntimeTypes.KotlinCoroutines.coroutineContext,
                RuntimeTypes.Tracing.Core.withRootTraceSpan,
            ) {
                if (hasOutputStream) {
                    write("op.#T(client, #L, block)", RuntimeTypes.Http.Operation.execute, inputVariableName)
                } else {
                    write("op.#T(client, #L)", RuntimeTypes.Http.Operation.roundTrip, inputVariableName)
                }
            }
    }

    private fun ioSymbolNames(op: OperationShape): Pair<String, String> {
        val opIndex = OperationIndex.of(ctx.model)
        val inputShape = opIndex.getInput(op)
        val outputShape = opIndex.getOutput(op)

        val inputSymbolName =
            inputShape.map { ctx.symbolProvider.toSymbol(it).name }.getOrNull() ?: KotlinTypes.Unit.fullName
        val outputSymbolName =
            outputShape.map { ctx.symbolProvider.toSymbol(it).name }.getOrNull() ?: KotlinTypes.Unit.fullName

        return Pair(inputSymbolName, outputSymbolName)
    }

    /**
     * Renders the operation specific middleware
     *
     * Example:
     * ```
     * op.install(<Middleware>)
     * ```
     */
    protected open fun renderOperationMiddleware(op: OperationShape, writer: KotlinWriter) {
        middleware
            .filter { it.isEnabledFor(ctx, op) }
            .sortedBy(ProtocolMiddleware::order)
            .forEach { middleware ->
                middleware.render(ctx, op, writer)
            }
        if (op.checksumRequired()) {
            writer.addImport(RuntimeTypes.Http.Middlware.Md5ChecksumMiddleware)
            writer.write("op.install(#T())", RuntimeTypes.Http.Middlware.Md5ChecksumMiddleware)
        }
    }

    /**
     * Render the client close implementation, the base behavior of which is to close any managed config resources.
     */
    protected open fun renderClose(writer: KotlinWriter) {
        writer.withBlock("override fun close() {", "}") {
            write("managedResources.unshareAll()")
        }
    }

    /**
     * Render any additional methods to support client operation
     */
    protected open fun renderAdditionalMethods(writer: KotlinWriter) { }
}

// TODO https://github.com/awslabs/aws-sdk-kotlin/issues/557
private fun OperationShape.checksumRequired(): Boolean =
    hasTrait<HttpChecksumRequiredTrait>() || getTrait<HttpChecksumTrait>()?.isRequestChecksumRequired == true
