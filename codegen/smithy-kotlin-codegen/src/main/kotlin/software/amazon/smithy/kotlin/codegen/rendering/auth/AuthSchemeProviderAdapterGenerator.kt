/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.kotlin.codegen.rendering.auth

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.kotlin.codegen.KotlinSettings
import software.amazon.smithy.kotlin.codegen.core.RuntimeTypes
import software.amazon.smithy.kotlin.codegen.core.clientName
import software.amazon.smithy.kotlin.codegen.core.withBlock
import software.amazon.smithy.kotlin.codegen.model.buildSymbol
import software.amazon.smithy.kotlin.codegen.rendering.endpoints.EndpointResolverAdapterGenerator
import software.amazon.smithy.kotlin.codegen.rendering.protocol.ProtocolGenerator

/**
 * Generates the adapter from the service type specific auth scheme provider and the generic one used to execute
 * a request.
 */
class AuthSchemeProviderAdapterGenerator {
    companion object {
        fun getSymbol(settings: KotlinSettings): Symbol = buildSymbol {
            val prefix = clientName(settings.sdkId)
            name = "${prefix}AuthSchemeProviderAdapter"
            namespace = "${settings.pkg.name}.auth"
            definitionFile = "$name.kt"
        }
    }

    fun render(ctx: ProtocolGenerator.GenerationContext) {
        val symbol = getSymbol(ctx.settings)
        ctx.delegator.useSymbolWriter(symbol) { writer ->
            writer.dokka("Adapts the service specific auth scheme resolver to the agnostic runtime interface and binds the auth parameters")
            writer.withBlock(
                "internal class #T(private val config: #T.Config): #T {",
                "}",
                symbol,
                ctx.symbolProvider.toSymbol(ctx.service),
                RuntimeTypes.HttpClient.Operation.AuthSchemeResolver,
            ) {
                withBlock(
                    "override suspend fun resolve(request: #T): List<#T> {",
                    "}",
                    RuntimeTypes.HttpClient.Operation.SdkHttpRequest,
                    RuntimeTypes.Auth.Identity.AuthOption,
                ) {
                    withBlock("val params = #T {", "}", AuthSchemeParametersGenerator.getSymbol(ctx.settings)) {
                        addImport(RuntimeTypes.Core.Collections.get)
                        write("operationName = request.context[#T.OperationName]", RuntimeTypes.SmithyClient.SdkClientOption)

                        if (ctx.settings.api.enableEndpointAuthProvider) {
                            write(
                                "val resolveEndpointReq = #T(request.context, request.subject.#T(), #T)",
                                RuntimeTypes.HttpClient.Operation.ResolveEndpointRequest,
                                RuntimeTypes.Http.Request.immutableView,
                                RuntimeTypes.Auth.HttpAuth.AnonymousIdentity,
                            )

                            write("endpointParameters = #T(config, resolveEndpointReq)", EndpointResolverAdapterGenerator.getResolveEndpointParamsFn(ctx.settings))
                        }
                    }

                    write("return config.authSchemeProvider.resolveAuthScheme(params)")
                }
            }
        }
    }
}
