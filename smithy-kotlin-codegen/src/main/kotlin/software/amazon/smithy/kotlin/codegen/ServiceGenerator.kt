/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package software.amazon.smithy.kotlin.codegen

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.codegen.core.SymbolProvider
import software.amazon.smithy.codegen.core.SymbolReference
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.knowledge.OperationIndex
import software.amazon.smithy.model.knowledge.TopDownIndex
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.IdempotencyTokenTrait
import software.amazon.smithy.model.traits.StreamingTrait

/**
 * Section name used when rendering the service interface companion object
 */
const val SECTION_SERVICE_INTERFACE_COMPANION_OBJ = "service-interface-companion-obj"

/**
 * Section name used when rendering the service interface configuration object
 */
const val SECTION_SERVICE_INTERFACE_CONFIG = "service-interface-config"

/**
 * HttpFeature interface that allows pipeline middleware to be registered and configured with the generator
 */
interface ServiceConfigFeature {
    /**
     * Supply parameters to be added to the constructor of the generated Config type.
     */
    fun supplyConstructorParameters(model: Model, service: ServiceShape, applicationProtocol: ApplicationProtocol, writer: KotlinWriter): List<String> = emptyList()

    /**
     * Supply types that the Config type implements.
     */
    fun supplyInterfaces(model: Model, service: ServiceShape, applicationProtocol: ApplicationProtocol, writer: KotlinWriter): List<String> = emptyList()
}

/**
 * Built-in config features for smithy-kotlin.
 */
private val defaultServiceConfigFeatures = listOf(
        object : ServiceConfigFeature {
            override fun supplyConstructorParameters(model: Model, service: ServiceShape, applicationProtocol: ApplicationProtocol, writer: KotlinWriter): List<String> {
                val params = mutableListOf<String>()

                if (applicationProtocol.isHttpProtocol) {
                    val engineSymbol = Symbol.builder()
                            .name("HttpClientEngine")
                            .namespace("${KotlinDependency.CLIENT_RT_HTTP.namespace}.engine", ".")
                            .addDependency(KotlinDependency.CLIENT_RT_HTTP)
                            .build()
                    writer.addImport(engineSymbol, "", SymbolReference.ContextOption.DECLARE)

                    params.add("var httpEngine: HttpClientEngine? = null")
                }

                if (service.hasIdempotentTokenMember(model)) {
                    val idempotencyTokenProviderSymbol = Symbol.builder()
                            .name("IdempotencyTokenProvider")
                            .namespace("${KotlinDependency.CLIENT_RT_CORE.namespace}.config", ".")
                            .addDependency(KotlinDependency.CLIENT_RT_CORE)
                            .build()
                    writer.addImport(idempotencyTokenProviderSymbol, "", SymbolReference.ContextOption.DECLARE)
                    params.add("var idempotencyTokenProvider: IdempotencyTokenProvider? = null")
                }

                return params
            }

            override fun supplyInterfaces(model: Model, service: ServiceShape, applicationProtocol: ApplicationProtocol, writer: KotlinWriter): List<String> {
                return emptyList()
            }
        }
)

/**
 * Renders just the service interfaces. The actual implementation is handled by protocol generators
 */
class ServiceGenerator(
    private val model: Model,
    private val symbolProvider: SymbolProvider,
    private val writer: KotlinWriter,
    private val service: ServiceShape,
    private val rootNamespace: String,
    private val applicationProtocol: ApplicationProtocol,
    private val integrationServiceConfigFeatures: List<ServiceConfigFeature>
) {
    private val serviceSymbol = symbolProvider.toSymbol(service)

    fun render() {

        importExternalSymbols()

        val topDownIndex = TopDownIndex.of(model)
        val operations = topDownIndex.getContainedOperations(service).sortedBy { it.defaultName() }
        val operationsIndex = OperationIndex.of(model)

        writer.renderDocumentation(service)
        writer.openBlock("interface ${serviceSymbol.name} : SdkClient {")
            .call { overrideServiceName() }
            .call {
                // allow integrations to add additional fields to companion object or configuration
                writer.write("")
                writer.pushState(SECTION_SERVICE_INTERFACE_COMPANION_OBJ)
                renderCompanionObject()
                writer.popState()

                writer.write("")
                writer.pushState(SECTION_SERVICE_INTERFACE_CONFIG)
                val allFeatures = defaultServiceConfigFeatures + integrationServiceConfigFeatures
                val configTypeIntegrations = generateConfigTypeIntegrations(allFeatures)
                writer.write("class Config$configTypeIntegrations")
                writer.popState()
            }
            .call {
                operations.forEach { op ->
                    renderOperation(operationsIndex, op)
                }
            }
            .closeBlock("}")
            .write("")
    }

    // Generate the override constructor params and interfaces for the service config
    private fun generateConfigTypeIntegrations(serviceConfigFeatures: List<ServiceConfigFeature>): String {
        val allParameters = serviceConfigFeatures.map { feature -> feature.supplyConstructorParameters(model, service, applicationProtocol, writer) }.flatten()
        val allInterfaces = serviceConfigFeatures.map { feature -> feature.supplyInterfaces(model, service, applicationProtocol, writer) }.flatten()

        if (allInterfaces.isEmpty() && allParameters.isEmpty()) return ""

        val parameters = allParameters.joinToString(separator = ", ", prefix = "(", postfix = ") ")
        val interfaces = allInterfaces.joinToString(separator = ", ", prefix = ": ", postfix = " ")

        return parameters + interfaces
    }

    /**
     * Render the service interface companion object which is the main entry point for most consumers
     *
     * e.g.
     * ```
     * companion object {
     *     fun build(block: Configuration.() -> Unit = {}): LambdaClient {
     *         val config = Configuration().apply(block)
     *         return DefaultLambdaClient(config)
     *     }
     * }
     * ```
     */
    private fun renderCompanionObject() {
        writer.openBlock("companion object {")
            .openBlock("fun build(block: Config.() -> Unit = {}): ${serviceSymbol.name} {")
            .write("val config = Config().apply(block)")
            .write("return Default${serviceSymbol.name}(config)")
            .closeBlock("}")
            .closeBlock("}")
    }

    private fun importExternalSymbols() {
        // base client interface
        val sdkInterfaceSymbol = Symbol.builder()
            .name("SdkClient")
            .namespace(CLIENT_RT_ROOT_NS, ".")
            .addDependency(KotlinDependency.CLIENT_RT_CORE)
            .build()

        writer.addImport(sdkInterfaceSymbol)

        // import all the models generated for use in input/output shapes
        writer.addImport("$rootNamespace.model", "*")
    }

    private fun overrideServiceName() {
        writer.write("")
            .write("override val serviceName: String")
            .indent()
            .write("get() = \"\$L\"", service.id.name)
            .dedent()
    }

    private fun renderOperation(opIndex: OperationIndex, op: OperationShape) {
        writer.write("")
        writer.renderDocumentation(op)
        writer.write(opIndex.operationSignature(model, symbolProvider, op))
    }
}

fun StructureShape.hasStreamingMember(model: Model): Boolean =
    this.allMembers.values.any { model.getShape(it.target).get().hasTrait(StreamingTrait::class.java) }

// Returns true if any operation bound to the service contains an input member marked with the IdempotencyTokenTrait
fun ServiceShape.hasIdempotentTokenMember(model: Model) =
    this.operations.any { operationShapeId ->
        val operation = model.expectShape(operationShapeId) as OperationShape
        operation.input.isPresent &&
            model.expectShape(operation.input.get()).members().any { it.hasTrait(IdempotencyTokenTrait.ID.name) }
    }

/**
 * Return the formatted (Kotlin) function signature for the given operation
 */
fun OperationIndex.operationSignature(model: Model, symbolProvider: SymbolProvider, op: OperationShape): String {
    val inputShape = this.getInput(op)
    val outputShape = this.getOutput(op)
    val input = inputShape.map { symbolProvider.toSymbol(it).name }
    val output = outputShape.map { symbolProvider.toSymbol(it).name }

    val hasOutputStream = outputShape.map { it.hasStreamingMember(model) }.orElse(false)
    val inputParam = input.map { "input: $it" }.orElse("")
    val outputParam = output.map { ": $it" }.orElse("")

    val operationName = op.defaultName()

    return if (!hasOutputStream) {
        "suspend fun $operationName($inputParam)$outputParam"
    } else {
        val outputName = output.get()
        val inputSignature = if (inputParam.isNotEmpty()) "$inputParam, " else ""
        "suspend fun <T> $operationName(${inputSignature}block: suspend ($outputName) -> T): T"
    }
}
