/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package software.amazon.smithy.kotlin.codegen.rendering

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.codegen.core.SymbolReference
import software.amazon.smithy.kotlin.codegen.core.*
import software.amazon.smithy.kotlin.codegen.model.boxed
import software.amazon.smithy.kotlin.codegen.model.buildSymbol
import software.amazon.smithy.kotlin.codegen.model.defaultValue
import software.amazon.smithy.kotlin.codegen.model.namespace

/**
 * Represents a service client config property to be added to the generated client
 *
 * e.g.
 *
 * ```
 * val myProp = ConfigProperty {
 *     symbol = buildSymbol { ... }
 *     documentation = "my property documentation"
 *     addBaseClass(myBaseClass)
 * }
 * ```
 */
class ClientConfigProperty private constructor(builder: Builder) {

    /**
     * The symbol (type) for the property
     *
     * NOTE: Use the extension properties on Symbol.Builder to set additional properties:
     * e.g.
     * ```
     * val symbol = Symbol.builder()
     *    .nullable = true // mark the symbol as nullable
     *    .defaultValue("foo") // set the default value for the property
     *    .build()
     * ```
     */
    val symbol: Symbol = requireNotNull(builder.symbol)

    /**
     * Help text to be rendered for the property
     */
    val documentation: String? = builder.documentation

    /**
     * The name of the property to render to config
     */
    val propertyName: String = builder.name ?: symbol.name.replaceFirstChar { c -> c.lowercaseChar() }

    /**
     * Additional base classes config should inherit from
     *
     * NOTE: Adding 1 or more base classes will implicitly render the property with an `override` modifier
     */
    val baseClass: Symbol? = builder.baseClass

    /**
     * The configuration property type. This controls how the property is constructed and rendered
     */
    val propertyType: ClientConfigPropertyType = builder.propertyType

    /**
     * Flag indicating if this property stems from some base class and needs an override modifier when rendered
     */
    internal val requiresOverride: Boolean
        get() = baseClass != null

    companion object {
        operator fun invoke(block: Builder.() -> Unit): ClientConfigProperty =
            Builder().apply(block).build()

        /**
         * Convenience init for an integer symbol.
         * @param name The property name
         * @param defaultValue The default value the config property should have if not set (if not specified the
         * parameter is assumed nullable)
         * @param documentation Help text to render as documentation for the property
         * @param baseClass Base class the config class should inherit from (assumes this property
         * stems from this type)
         */
        fun Int(
            name: String,
            defaultValue: Int? = null,
            documentation: String? = null,
            baseClass: Symbol? = null,
        ): ClientConfigProperty =
            builtInProperty(name, builtInSymbol("Int", defaultValue?.toString()), documentation, baseClass)

        /**
         * Convenience init for a boolean symbol.
         * @param name The property name
         * @param defaultValue The default value the config property should have if not set (if not specified the
         * parameter is assumed nullable)
         * @param documentation Help text to render as documentation for the property
         * @param baseClass Base class the config class should inherit from (assumes this property
         * stems from this type)
         */
        fun Boolean(
            name: String,
            defaultValue: Boolean? = null,
            documentation: String? = null,
            baseClass: Symbol? = null,
        ): ClientConfigProperty =
            builtInProperty(name, builtInSymbol("Boolean", defaultValue?.toString()), documentation, baseClass)

        /**
         * Convenience init for a string symbol.
         * @param name The property name
         * @param defaultValue The default value the config property should have if not set (if not specified the
         * parameter is assumed nullable)
         * @param documentation Help text to render as documentation for the property
         * @param baseClass Base class the config class should inherit from (assumes this property
         * stems from this type)
         */
        fun String(
            name: String,
            defaultValue: String? = null,
            documentation: String? = null,
            baseClass: Symbol? = null,
        ): ClientConfigProperty =
            builtInProperty(name, builtInSymbol("String", defaultValue), documentation, baseClass)
    }

    class Builder {
        var symbol: Symbol? = null
        // override the property name (defaults to symbol name)
        var name: String? = null
        var documentation: String? = null

        var baseClass: Symbol? = null

        var propertyType: ClientConfigPropertyType = ClientConfigPropertyType.SymbolDefault

        fun build(): ClientConfigProperty = ClientConfigProperty(this)
    }
}

/**
 * Descriptor for how a configuration property is rendered when the configuration is built
 */
sealed class ClientConfigPropertyType {
    /**
     * A property type that uses the symbol type and builder symbol directly
     */
    object SymbolDefault : ClientConfigPropertyType()

    /**
     * Specifies that the value should be populated with a constant value that cannot be overridden in the builder.
     * These are effectively read-only properties that will show up in the configuration type but not the builder.
     *
     * @param value the value to assign to the property at construction time
     */
    data class ConstantValue(val value: String) : ClientConfigPropertyType()

    /**
     * A configuration property that is required to be set (i.e. not null).
     * If the property is not provided in the builder then an IllegalArgumentException is thrown
     *
     * @param message The exception message to throw if the property is null, if not set a message is generated
     * automatically based on the property name
     */
    data class Required(val message: String? = null) : ClientConfigPropertyType()

    /**
     * A configuration property that is required but has a default value. This has the same semantics of [Required]
     * but instead of an exception the default value will be used when not provided in the builder.
     *
     * @param default the value to assign if the corresponding builder property is null
     */
    data class RequiredWithDefault(val default: String) : ClientConfigPropertyType()
}

private fun builtInSymbol(symbolName: String, defaultValue: String?): Symbol {
    val builder = Symbol.builder()
        .name(symbolName)

    if (defaultValue != null) {
        builder.defaultValue(defaultValue)
    } else {
        builder.boxed()
    }
    return builder.build()
}

private fun builtInProperty(
    name: String,
    symbol: Symbol,
    documentation: String?,
    baseClass: Symbol?,
): ClientConfigProperty =
    ClientConfigProperty {
        this.symbol = symbol
        this.name = name
        this.documentation = documentation
        this.baseClass = baseClass
    }

/**
 * Common client runtime related config properties
 */
object KotlinClientRuntimeConfigProperty {
    val HttpClientEngine: ClientConfigProperty
    val IdempotencyTokenProvider: ClientConfigProperty
    val RetryStrategy: ClientConfigProperty
    val SdkLogMode: ClientConfigProperty

    init {
        val httpClientConfigSymbol = buildSymbol {
            name = "HttpClientConfig"
            namespace(KotlinDependency.HTTP, "config")
        }

        HttpClientEngine = ClientConfigProperty {
            symbol = buildSymbol {
                name = "HttpClientEngine"
                namespace(KotlinDependency.HTTP, "engine")
            }
            baseClass = httpClientConfigSymbol
            documentation = """
            Override the default HTTP client engine used to make SDK requests (e.g. configure proxy behavior, timeouts, concurrency, etc)    
            """.trimIndent()
        }

        IdempotencyTokenProvider = ClientConfigProperty {
            symbol = buildSymbol {
                name = "IdempotencyTokenProvider"
                namespace(KotlinDependency.CORE, "config")
            }

            baseClass = buildSymbol {
                name = "IdempotencyTokenConfig"
                namespace(KotlinDependency.CORE, "config")
            }

            documentation = """
            Override the default idempotency token generator. SDK clients will generate tokens for members
            that represent idempotent tokens when not explicitly set by the caller using this generator.
            """.trimIndent()
        }

        RetryStrategy = ClientConfigProperty {
            val retryStrategyBlock = """
                run {
                    val strategyOptions = StandardRetryStrategyOptions.Default
                    val tokenBucket = StandardRetryTokenBucket(StandardRetryTokenBucketOptions.Default)
                    val delayer = ExponentialBackoffWithJitter(ExponentialBackoffWithJitterOptions.Default)
                    StandardRetryStrategy(strategyOptions, tokenBucket, delayer)
                }
            """.trimIndent()

            symbol = buildSymbol {
                name = "RetryStrategy"
                namespace(KotlinDependency.CORE, "retries")
                nullable = false
                reference(RuntimeTypes.Core.Retries.Impl.StandardRetryStrategy, SymbolReference.ContextOption.USE)
                reference(RuntimeTypes.Core.Retries.Impl.StandardRetryStrategyOptions, SymbolReference.ContextOption.USE)
                reference(RuntimeTypes.Core.Retries.Impl.StandardRetryTokenBucket, SymbolReference.ContextOption.USE)
                reference(RuntimeTypes.Core.Retries.Impl.StandardRetryTokenBucketOptions, SymbolReference.ContextOption.USE)
                reference(RuntimeTypes.Core.Retries.Impl.ExponentialBackoffWithJitter, SymbolReference.ContextOption.USE)
                reference(RuntimeTypes.Core.Retries.Impl.ExponentialBackoffWithJitterOptions, SymbolReference.ContextOption.USE)
            }
            name = "retryStrategy"
            documentation = """
                The [RetryStrategy] implementation to use for service calls. All API calls will be wrapped by the
                strategy.
            """.trimIndent()

            propertyType = ClientConfigPropertyType.ConstantValue(retryStrategyBlock)
        }

        SdkLogMode = ClientConfigProperty {
            symbol = buildSymbol {
                name = "SdkLogMode"
                namespace(KotlinDependency.CORE, "client")
                defaultValue = "SdkLogMode.Default"
                nullable = false
            }

            baseClass = buildSymbol {
                name = "SdkClientConfig"
                namespace(KotlinDependency.CORE, "config")
            }

            documentation = """
            Configure events that will be logged. By default clients will not output
            raw requests or responses. Use this setting to opt-in to additional debug logging.

            This can be used to configure logging of requests, responses, retries, etc of SDK clients.

            **NOTE**: Logging of raw requests or responses may leak sensitive information! It may also have
            performance considerations when dumping the request/response body. This is primarily a tool for
            debug purposes.
            """.trimIndent()
        }
    }
}
