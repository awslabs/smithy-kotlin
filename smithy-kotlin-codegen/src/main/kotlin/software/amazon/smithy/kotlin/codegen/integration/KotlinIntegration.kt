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
package software.amazon.smithy.kotlin.codegen.integration

import software.amazon.smithy.codegen.core.SymbolProvider
import software.amazon.smithy.kotlin.codegen.*
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.Shape

/**
 * JVM SPI for customizing Kotlin code generation, registering new protocol
 * generators, renaming shapes, modifying the model, adding custom code, etc
 */
interface KotlinIntegration {

    /**
     * Gets the sort order of the customization from -128 to 127.
     *
     * Customizations are applied according to this sort order. Lower values
     * are executed before higher values (for example, -128 comes before 0,
     * comes before 127). Customizations default to 0, which is the middle point
     * between the minimum and maximum order values. The customization
     * applied later can override the runtime configurations that provided
     * by customizations applied earlier.
     *
     * @return Returns the sort order, defaulting to 0.
     */
    val order: Byte
        get() = 0

    /**
     * Get the list of protocol generators to register
     */
    val protocolGenerators: List<ProtocolGenerator>
        get() = listOf()

    /**
     * Additional properties to be add to the generated service config interface
     * @param ctx The current codegen context. This allows integrations to filter properties
     * by things like the protocol being generated for, settings, etc.
     */
    fun additionalServiceConfigProps(ctx: CodegenContext): List<ClientConfigProperty> {
        return listOf()
    }

    /**
     * Preprocess the model before code generation.
     *
     * This can be used to remove unsupported features, remove traits
     * from shapes (e.g., make members optional), etc.
     *
     * @param model model definition.
     * @param settings Setting used to generate.
     * @return Returns the updated model.
     */
    fun preprocessModel(model: Model, settings: KotlinSettings): Model = model

    /**
     * Updates the [SymbolProvider] used when generating code.
     *
     * This can be used to customize the names of shapes, the package
     * that code is generated into, add dependencies, add imports, etc.
     *
     * @param settings Setting used to generate.
     * @param model Model being generated.
     * @param symbolProvider The original `SymbolProvider`.
     * @return The decorated `SymbolProvider`.
     */
    fun decorateSymbolProvider(
        settings: KotlinSettings,
        model: Model,
        symbolProvider: SymbolProvider
    ): SymbolProvider {
        return symbolProvider
    }

    /**
     * Called each time a writer is used that defines a shape.
     *
     * Any mutations made on the writer (for example, adding
     * section interceptors) are removed after the callback has completed;
     * the callback is invoked in between pushing and popping state from
     * the writer.
     *
     * @param settings Settings used to generate.
     * @param model Model to generate from.
     * @param symbolProvider Symbol provider used for codegen.
     * @param writer Writer that will be used.
     * @param definedShape Shape that is being defined in the writer.
     */
    fun onShapeWriterUse(
        settings: KotlinSettings,
        model: Model,
        symbolProvider: SymbolProvider,
        writer: KotlinWriter,
        definedShape: Shape
    ) {
        // pass
    }

    /**
     * Write additional files defined by this integration
     * @param ctx The codegen generation context
     * @param delegator File writer(s)
     */
    fun writeAdditionalFiles(
        ctx: CodegenContext,
        delegator: KotlinDelegator
    ) {
        // pass
    }

    /**
     * Customize the middleware to use when generating a protocol client/service implementation. By default
     * the [resolved] is returned unmodified. Integrations are allowed to add/remove/re-order the middleware.
     *
     * NOTE: Protocol generators should only allow integrations to customize AFTER they have resolved the default set
     * of middleware for the protocol (if any).
     *
     * @param ctx The codegen generation context
     * @param resolved The middleware resolved by the protocol generator
     */
    fun customizeMiddleware(
        // ctx: CodegenContext,
        ctx: ProtocolGenerator.GenerationContext,
        resolved: List<ProtocolMiddleware>
    ): List<ProtocolMiddleware> {
        return resolved
    }
}
