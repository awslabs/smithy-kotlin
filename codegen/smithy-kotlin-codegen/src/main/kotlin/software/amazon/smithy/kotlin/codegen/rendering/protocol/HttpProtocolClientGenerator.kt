/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.kotlin.codegen.rendering.protocol

import software.amazon.smithy.aws.traits.HttpChecksumTrait
import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.kotlin.codegen.core.*
import software.amazon.smithy.kotlin.codegen.integration.SectionId
import software.amazon.smithy.kotlin.codegen.integration.SectionKey
import software.amazon.smithy.kotlin.codegen.lang.KotlinTypes
import software.amazon.smithy.kotlin.codegen.model.*
import software.amazon.smithy.kotlin.codegen.model.knowledge.AuthIndex
import software.amazon.smithy.kotlin.codegen.rendering.auth.AuthSchemeProviderAdapterGenerator
import software.amazon.smithy.kotlin.codegen.rendering.auth.IdentityProviderConfigGenerator
import software.amazon.smithy.kotlin.codegen.rendering.endpoints.EndpointResolverAdapterGenerator
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
open class HttpProtocolClientGenerator(
    protected val ctx: ProtocolGenerator.GenerationContext,
    protected val middleware: List<ProtocolMiddleware>,
    protected val httpBindingResolver: HttpBindingResolver,
) {
    object EndpointResolverAdapterBinding : SectionId {
        val GenerationContext: SectionKey<ProtocolGenerator.GenerationContext> = SectionKey("GenerationContext")
        val OperationShape: SectionKey<OperationShape> = SectionKey("OperationShape")
    }

    object OperationTelemetryBuilder : SectionId {
        val Operation: SectionKey<OperationShape> = SectionKey("Operation")
    }

    object ClientInitializer : SectionId {
        val GenerationContext: SectionKey<ProtocolGenerator.GenerationContext> = SectionKey("GenerationContext")
    }

    object MergeServiceDefaults : SectionId {
        val GenerationContext: SectionKey<ProtocolGenerator.GenerationContext> = SectionKey("GenerationContext")
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
            .call { renderMergeServiceDefaults(writer) }
            .write("")
            .call { renderAdditionalMethods(writer) }
            .closeBlock("}")
            .write("")
    }

    /**
     * Render any properties this class should have.
     */
    protected open fun renderProperties(writer: KotlinWriter) {
        writer.write("private val managedResources = #T()", RuntimeTypes.Core.IO.SdkManagedGroup)
        writer.write("private val client = #T(config.httpClient)", RuntimeTypes.HttpClient.SdkHttpClient)

        // render auth resolver related properties
        writer.write("private val identityProviderConfig = #T(config)", IdentityProviderConfigGenerator.getSymbol(ctx.settings))

        // FIXME - we probably need a way for auth handlers to signal that they are configured (e.g. config properties are not null). Right now this assumes
        // they are all configured but a service may support multiple auth schemes and a client may not need to configure all of them
        writer.withBlock(
            "private val configuredAuthSchemes = with(config.authSchemes.associateBy(#T::schemeId).toMutableMap()){",
            "}",
            RuntimeTypes.Auth.HttpAuth.AuthScheme,
        ) {
            val authIndex = AuthIndex()
            val allAuthHandlers = authIndex.authHandlersForService(ctx)

            allAuthHandlers.forEach {
                val (format, args) = if (it.authSchemeIdSymbol != null) {
                    "#T" to arrayOf(it.authSchemeIdSymbol!!)
                } else {
                    "#T(#S)" to arrayOf(RuntimeTypes.Auth.Identity.AuthSchemeId, it.authSchemeId)
                }

                withBlock(
                    "getOrPut($format){",
                    "}",
                    *args,
                ) {
                    it.instantiateAuthSchemeExpr(ctx, this)
                }
            }

            write("toMap()")
        }
        writer.write("private val authSchemeAdapter = #T(config)", AuthSchemeProviderAdapterGenerator.getSymbol(ctx.settings))

        writer.write("private val telemetryScope = #S", ctx.settings.pkg.name)
        writer.write("private val opMetrics = #T(telemetryScope, config.telemetryProvider)", RuntimeTypes.HttpClient.Operation.OperationMetrics)
    }

    protected open fun importSymbols(writer: KotlinWriter) {
        writer.addImport(ctx.settings.pkg.subpackage("model"), "*")
        if (TopDownIndex(ctx.model).getContainedOperations(ctx.service).isNotEmpty()) {
            writer.addImport(ctx.settings.pkg.serde, "*")
        }

        val defaultClientSymbols = setOf(
            RuntimeTypes.HttpClient.Operation.SdkHttpOperation,
            RuntimeTypes.HttpClient.Operation.context,
        )
        writer.addImport(defaultClientSymbols)
        writer.dependencies.addAll(KotlinDependency.HTTP.dependencies)
    }

    /**
     * Render the class initialization block.
     */
    protected open fun renderInit(writer: KotlinWriter) {
        writer.withBlock("init {", "}") {
            write("managedResources.#T(config.httpClient)", RuntimeTypes.Core.IO.addIfManaged)
            writer.declareSection(ClientInitializer, mapOf(ClientInitializer.GenerationContext to ctx))
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
                writer.write("serializeWith = ${op.serializerName()}()")
            } else {
                // no serializer implementation is generated for operations with no input, inline the HTTP
                // protocol request from the operation itself
                // NOTE: this will never be triggered for AWS models where we preprocess operations to always have inputs/outputs
                writer.addImport(RuntimeTypes.Http.Request.HttpRequestBuilder)
                writer.addImport(RuntimeTypes.Core.ExecutionContext)
                writer.openBlock("serializer = object : #T.NonStreaming<#Q> {", "}", RuntimeTypes.HttpClient.Operation.HttpSerializer, KotlinTypes.Unit) {
                    writer.openBlock(
                        "override fun serialize(context: ExecutionContext, input: #Q): HttpRequestBuilder {",
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

            if (outputShape.isPresent) {
                writer.write("deserializeWith = ${op.deserializerName()}()")
            } else {
                writer.write("deserializer = UnitDeserializer")
            }

            writer.write("operationName = #S", op.id.name)
            writer.write("serviceName = #L", "ServiceId")

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

            // telemetry
            writer.withBlock("#T {", "}", RuntimeTypes.HttpClient.Operation.telemetry) {
                write("provider = config.telemetryProvider")
                write("scope = telemetryScope")
                write("metrics = opMetrics")
                writer.declareSection(OperationTelemetryBuilder, mapOf(OperationTelemetryBuilder.Operation to op))
            }

            writer.write(
                "execution.auth = #T(authSchemeAdapter, configuredAuthSchemes, identityProviderConfig)",
                RuntimeTypes.HttpClient.Operation.OperationAuthConfig,
            )

            writer.declareSection(
                EndpointResolverAdapterBinding,
                mapOf(
                    EndpointResolverAdapterBinding.GenerationContext to ctx,
                    EndpointResolverAdapterBinding.OperationShape to op,
                ),
            ) {
                write("execution.endpointResolver = #T(config)", EndpointResolverAdapterGenerator.getSymbol(ctx.settings))
            }

            writer.write("execution.retryStrategy = config.retryStrategy")
            writer.write("execution.retryPolicy = config.retryPolicy")
        }

        writer.write("mergeServiceDefaults(op.context)")
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

        if (hasOutputStream) {
            writer.write("return op.#T(client, #L, block)", RuntimeTypes.HttpClient.Operation.execute, inputVariableName)
        } else {
            writer.write("return op.#T(client, #L)", RuntimeTypes.HttpClient.Operation.roundTrip, inputVariableName)
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

        op.renderIsMd5ChecksumRequired(writer)
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

    /**
     * Render optionally installing Md5ChecksumMiddleware.
     * The Md5 middleware will only be installed if the operation requires a checksum and the user has not opted-in to flexible checksums.
     */
    private fun OperationShape.renderIsMd5ChecksumRequired(writer: KotlinWriter) {
        val httpChecksumTrait = getTrait<HttpChecksumTrait>()

        // the checksum requirement can be modeled in either HttpChecksumTrait's `requestChecksumRequired` or the HttpChecksumRequired trait
        if (!hasTrait<HttpChecksumRequiredTrait>() && httpChecksumTrait == null) {
            return
        }

        if (hasTrait<HttpChecksumRequiredTrait>() || httpChecksumTrait?.isRequestChecksumRequired == true) {
            val interceptorSymbol = RuntimeTypes.HttpClient.Interceptors.Md5ChecksumInterceptor
            val inputSymbol = ctx.symbolProvider.toSymbol(ctx.model.expectShape(inputShape))
            writer.withBlock("op.interceptors.add(#T<#T> {", "})", interceptorSymbol, inputSymbol) {
                writer.write("op.context.getOrNull(#T.ChecksumAlgorithm) == null", RuntimeTypes.HttpClient.Operation.HttpOperationContext)
            }
        }
    }

    /**
     * render a utility function to populate an operation's ExecutionContext with defaults from service config, environment, etc
     */
    private fun renderMergeServiceDefaults(writer: KotlinWriter) {
        writer.dokka("merge the defaults configured for the service into the execution context before firing off a request")
        writer.withBlock(
            "private fun mergeServiceDefaults(ctx: #T) {",
            "}",
            RuntimeTypes.Core.ExecutionContext,
        ) {
            putIfAbsent(RuntimeTypes.SmithyClient.SdkClientOption, "ClientName")
            putIfAbsent(RuntimeTypes.SmithyClient.SdkClientOption, "LogMode")
            if (ctx.service.hasIdempotentTokenMember(ctx.model)) {
                putIfAbsent(RuntimeTypes.SmithyClient.SdkClientOption, "IdempotencyTokenProvider", nullable = true)
            }

            writer.declareSection(MergeServiceDefaults, mapOf(MergeServiceDefaults.GenerationContext to ctx))
        }
    }
}

/**
 * Convenience extension for writing to the operation execution context
 */
fun KotlinWriter.putIfAbsent(
    attributesSymbol: Symbol,
    name: String,
    literalValue: String? = null,
    nullable: Boolean = false,
) {
    val putSymbol = if (nullable) RuntimeTypes.Core.Collections.putIfAbsentNotNull else RuntimeTypes.Core.Collections.putIfAbsent
    val actualValue = literalValue ?: "config.${name.replaceFirstChar(Char::lowercaseChar)}"
    write("ctx.#T(#T.#L, #L)", putSymbol, attributesSymbol, name, actualValue)
}
