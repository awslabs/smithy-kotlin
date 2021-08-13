/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package software.amazon.smithy.kotlin.codegen.core

import software.amazon.smithy.codegen.core.CodegenException
import software.amazon.smithy.codegen.core.SymbolDependency
import software.amazon.smithy.codegen.core.SymbolDependencyContainer
import software.amazon.smithy.utils.StringUtils

// root namespace for the runtime
const val RUNTIME_ROOT_NS = "aws.smithy.kotlin.runtime"

/**
 * Test if a string represents a valid artifact version string
 */
fun isValidVersion(version: String): Boolean {
    val re = Regex("\\d\\.\\d\\.\\d[a-z0-9A-Z.-]*\$")
    return re.matches(version)
}

private fun getDefaultRuntimeVersion(): String {
    // generated as part of the build, see smithy-kotlin-codegen/build.gradle.kts
    try {
        val version = object {}.javaClass.getResource("sdk-version.txt")?.readText() ?: throw CodegenException("sdk-version.txt does not exist")
        check(isValidVersion(version)) { "Version parsed from sdk-version.txt '$version' is not a valid version string" }
        return version
    } catch (ex: Exception) {
        throw CodegenException("failed to load sdk-version.txt which sets the default client-runtime version", ex)
    }
}

// publishing info
const val RUNTIME_GROUP: String = "aws.smithy.kotlin"
val RUNTIME_VERSION: String = System.getProperty("smithy.kotlin.codegen.clientRuntimeVersion", getDefaultRuntimeVersion())
val KOTLIN_COMPILER_VERSION: String = System.getProperty("smithy.kotlin.codegen.kotlinCompilerVersion", "1.5.20")

// See: https://docs.gradle.org/current/userguide/java_library_plugin.html#sec:java_library_configurations_graph
enum class GradleConfiguration {
    // purely internal and not meant to be exposed to consumers.
    Implementation,
    // transitively exported to consumers, for compile.
    Api,
    // only required at compile time, but should not leak into the runtime
    CompileOnly,
    // only required at runtime
    RuntimeOnly,
    // internal test
    TestImplementation,
    // compile time test only
    TestCompileOnly,
    // compile time runtime only
    TestRuntimeOnly;

    override fun toString(): String = StringUtils.uncapitalize(this.name)
}

data class KotlinDependency(
    val config: GradleConfiguration,
    val namespace: String,
    val group: String,
    val artifact: String,
    val version: String
) : SymbolDependencyContainer {

    companion object {
        // AWS managed dependencies
        val CORE = KotlinDependency(GradleConfiguration.Api, RUNTIME_ROOT_NS, RUNTIME_GROUP, "runtime-core", RUNTIME_VERSION)
        val HTTP = KotlinDependency(GradleConfiguration.Implementation, "$RUNTIME_ROOT_NS.http", RUNTIME_GROUP, "http", RUNTIME_VERSION)
        val SERDE = KotlinDependency(GradleConfiguration.Implementation, "$RUNTIME_ROOT_NS.serde", RUNTIME_GROUP, "serde", RUNTIME_VERSION)
        val SERDE_JSON = KotlinDependency(GradleConfiguration.Implementation, "$RUNTIME_ROOT_NS.serde.json", RUNTIME_GROUP, "serde-json", RUNTIME_VERSION)
        val SERDE_XML = KotlinDependency(GradleConfiguration.Implementation, "$RUNTIME_ROOT_NS.serde.xml", RUNTIME_GROUP, "serde-xml", RUNTIME_VERSION)
        val SERDE_FORM_URL = KotlinDependency(GradleConfiguration.Implementation, "$RUNTIME_ROOT_NS.serde.formurl", RUNTIME_GROUP, "serde-form-url", RUNTIME_VERSION)
        val HTTP_KTOR_ENGINE = KotlinDependency(GradleConfiguration.Implementation, "$RUNTIME_ROOT_NS.http.engine.ktor", RUNTIME_GROUP, "http-client-engine-ktor", RUNTIME_VERSION)
        val UTILS = KotlinDependency(GradleConfiguration.Implementation, "$RUNTIME_ROOT_NS.util", RUNTIME_GROUP, "utils", RUNTIME_VERSION)
        val SMITHY_TEST = KotlinDependency(GradleConfiguration.TestImplementation, "$RUNTIME_ROOT_NS.smithy.test", RUNTIME_GROUP, "smithy-test", RUNTIME_VERSION)

        // External third-party dependencies
        val KOTLIN_TEST = KotlinDependency(GradleConfiguration.TestImplementation, "kotlin.test", "org.jetbrains.kotlin", "kotlin-test", KOTLIN_COMPILER_VERSION)
        val KOTLIN_TEST_JUNIT5 = KotlinDependency(GradleConfiguration.TestImplementation, "kotlin.test.junit5", "org.jetbrains.kotlin", "kotlin-test-junit5", KOTLIN_COMPILER_VERSION)
        val JUNIT_JUPITER_ENGINE = KotlinDependency(GradleConfiguration.TestRuntimeOnly, "org.junit.jupiter", "org.junit.jupiter", "junit-jupiter-engine", "5.4.2")
    }

    override fun getDependencies(): List<SymbolDependency> {
        val dependency = SymbolDependency.builder()
            .dependencyType(config.name)
            .packageName(namespace)
            .version(version)
            .putProperty("dependency", this)
            .build()
        return listOf(dependency)
    }
}
