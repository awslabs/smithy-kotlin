/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.amazon.smithy.kotlin.codegen.rendering

import software.amazon.smithy.codegen.core.CodegenException
import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.kotlin.codegen.KotlinSettings
import software.amazon.smithy.kotlin.codegen.core.*
import software.amazon.smithy.kotlin.codegen.integration.SectionId
import software.amazon.smithy.kotlin.codegen.model.buildSymbol
import software.amazon.smithy.kotlin.codegen.rendering.protocol.ProtocolGenerator
import software.amazon.smithy.model.Model

/**
 * Renders the base class that all (modeled) exceptions inherit from.
 * Protocol generators are allowed to override this but they MUST inherit from the base `ServiceException`
 * with the expected constructors.
 */
object ExceptionBaseClassGenerator {

    /**
     * Defines a section in which code can be added to the body of the base exception type.
     */
    object ExceptionBaseClassSection : SectionId

    fun render(ctx: CodegenContext, writer: KotlinWriter) {
        val baseException = ctx.protocolGenerator?.exceptionBaseClassSymbol ?: ProtocolGenerator.DefaultServiceExceptionSymbol
        writer.addImport(baseException)
        val serviceException = baseExceptionSymbol(ctx.settings).also { checkForCollision(ctx.model, it) }

        val name = clientName(ctx.settings.sdkId)
        writer.dokka("Base class for all service related exceptions thrown by the $name client")
        writer.withBlock(
            "open class #T : #T {", "}",
            serviceException,
            baseException
        ) {
            write("constructor() : super()")
            write("constructor(message: String?) : super(message)")
            write("constructor(message: String?, cause: Throwable?) : super(message, cause)")
            write("constructor(cause: Throwable?) : super(cause)")

            writer.declareSection(ExceptionBaseClassSection)
        }
    }

    /**
     * Get the (generated) symbol that constitutes the base class exceptions will inherit from
     */
    fun baseExceptionSymbol(settings: KotlinSettings): Symbol = buildSymbol {
        val serviceName = clientName(settings.sdkId)
        name = "${serviceName}Exception"
        namespace = "${settings.pkg.name}.model"
        definitionFile = "$name.kt"
    }

    // Compare generated base exception name with all error type names.  Throw exception if not unique.
    private fun checkForCollision(model: Model, exceptionSymbol: Symbol) {
        model.operationShapes.forEach { operationShape ->
            val errorNameToShapeIndex = operationShape.errors.map { shapeId -> shapeId.name to shapeId }.toMap()
            if (errorNameToShapeIndex.containsKey(exceptionSymbol.name)) {
                throw CodegenException("Generated base error type '${exceptionSymbol.name}' collides with ${errorNameToShapeIndex[exceptionSymbol.name]}.")
            }
        }
    }
}
