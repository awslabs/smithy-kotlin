package software.amazon.smithy.kotlin.codegen.test

import software.amazon.smithy.build.MockManifest
import software.amazon.smithy.codegen.core.SymbolProvider
import software.amazon.smithy.kotlin.codegen.*
import software.amazon.smithy.kotlin.codegen.integration.ProtocolGenerator
import software.amazon.smithy.kotlin.codegen.model.OperationNormalizer
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.SmithyIdlModelSerializer
import java.net.URL

/**
 * This file houses classes and functions to help with testing with Smithy models.
 *
 * These functions should be relatively low-level and deal directly with types provided
 * by smithy-codegen.
 */

/**
 * Unless necessary to deviate for test reasons, the following literals should be used in test models:
 *  smithy version: "1"
 *  model version: "1.0.0"
 *  namespace: TestDefault.NAMESPACE
 *  service name: "Test"
 */
object TestDefault {
    const val SMITHY_IDL_VERSION = "1"
    const val MODEL_VERSION = "1.0.0"
    const val NAMESPACE = "com.test"
    const val SERVICE_NAME = "Test"
    const val SDK_ID = "Test"
    const val SERVICE_SHAPE_ID = "com.test#Test"
}

// attempt to replicate transforms that happen in CodegenVisitor such that tests
// more closely reflect reality
private fun Model.applyKotlinCodegenTransforms(serviceShapeId: String?): Model {
    val serviceId = if (serviceShapeId != null) ShapeId.from(serviceShapeId) else {
        // try to autodiscover the service so that tests "Just Work" (TM) without having to do anything
        val services = this.shapes<ServiceShape>()
        check(services.size <= 1) { "multiple services discovered in model; auto inference of service shape impossible for test. Fix by passing the service shape explicitly" }
        if (services.isEmpty()) return this // no services defined, move along
        services.first().id
    }

    val transforms = listOf(OperationNormalizer::transform)
    return transforms.fold(this) { m, transform ->
        transform(m, serviceId)
    }
}

/**
 * Load and initialize a model from a Java resource URL
 */
fun URL.asSmithy(serviceShapeId: String? = null): Model {
    val model = Model.assembler()
        .addImport(this)
        .discoverModels()
        .assemble()
        .unwrap()

    return model.applyKotlinCodegenTransforms(serviceShapeId)
}

/**
 * Load and initialize a model from a String
 */
fun String.asSmithyModel(sourceLocation: String? = null, serviceShapeId: String? = null, applyDefaultTransforms: Boolean = true): Model {
    val processed = if (this.trimStart().startsWith("\$version")) this else "\$version: \"1.0\"\n$this"
    val model = Model.assembler()
        .discoverModels()
        .addUnparsedModel(sourceLocation ?: "test.smithy", processed)
        .assemble()
        .unwrap()
    return if (applyDefaultTransforms) model.applyKotlinCodegenTransforms(serviceShapeId) else model
}

/**
 * Generate Smithy IDL from a model instance.
 *
 * NOTE: this is used for debugging / unit test generation, please don't remove.
 */
fun Model.toSmithyIDL(): String {
    val builtInModelIds = setOf("smithy.test.smithy", "aws.auth.smithy", "aws.protocols.smithy", "aws.api.smithy")
    val ms: SmithyIdlModelSerializer = SmithyIdlModelSerializer.builder().build()
    val node = ms.serialize(this)

    return node.filterNot { builtInModelIds.contains(it.key.toString()) }.values.first()
}

/**
 * Initiate codegen for the model and produce a [TestContext].
 *
 * @param serviceShapeId the smithy Id for the service to codegen, defaults to "com.test#Example"
 */
fun Model.newTestContext(
    serviceName: String = TestDefault.SERVICE_NAME,
    packageName: String = TestDefault.NAMESPACE,
    settings: KotlinSettings = this.defaultSettings(serviceName, packageName),
    generator: ProtocolGenerator = MockHttpProtocolGenerator()
): TestContext {
    val manifest = MockManifest()
    val provider: SymbolProvider = KotlinCodegenPlugin.createSymbolProvider(this, packageName, serviceName)
    val service = this.getShape(ShapeId.from("$packageName#$serviceName")).get().asServiceShape().get()
    val delegator = KotlinDelegator(settings, this, manifest, provider)

    val ctx = ProtocolGenerator.GenerationContext(
        settings,
        this,
        service,
        provider,
        listOf(),
        generator.protocol,
        delegator
    )
    return TestContext(ctx, manifest, generator)
}

/**
 * Generate a KotlinSettings instance from a model.
 * @param packageName name of module or DEFAULT_SERVICE_NAME if unspecified
 * @param packageVersion version of module or DEFAULT_MODEL_VERSION if unspecified
 *
 * Example:
 *  {
 *  	"service": "com.amazonaws.lambda#AWSGirApiService",
 *  	"package": {
 *  		"name": "aws.sdk.kotlin.services.lambda",
 *  		"version": "0.2.0-SNAPSHOT",
 *  		"description": "AWS Lambda"
 *  	},
 *  	"sdkId": "Lambda",
 *  	"build": {
 *  		"generateDefaultBuildFiles": false
 *  	}
 *  }
 */
internal fun Model.defaultSettings(
    serviceName: String? = null,
    packageName: String = TestDefault.NAMESPACE,
    packageVersion: String = TestDefault.MODEL_VERSION,
    sdkId: String = TestDefault.SDK_ID,
    generateDefaultBuildFiles: Boolean = false
): KotlinSettings {
    val serviceId = if (serviceName == null) {
        KotlinSettings.inferService(this)
    } else {
        this.getShape(ShapeId.from("$packageName#$serviceName")).getOrNull()?.id
            ?: error("Unable to find service '$serviceName' in:\n ${toSmithyIDL()}")
    }

    return KotlinSettings.from(
        this,
        Node.objectNodeBuilder()
            .withMember("service", Node.from(serviceId.toString()))
            .withMember(
                "package",
                Node.objectNode()
                    .withMember("name", Node.from(packageName))
                    .withMember("version", Node.from(packageVersion))
            )
            .withMember("sdkId", Node.from(sdkId))
            .withMember(
                "build",
                Node.objectNode()
                    .withMember("generateDefaultBuildFiles", Node.from(generateDefaultBuildFiles))
            )
            .build()
    )
}

fun String.generateTestModel(
    protocol: String,
    namespace: String = TestDefault.NAMESPACE,
    serviceName: String = TestDefault.SERVICE_NAME,
    operations: List<String>
): Model {
    val completeModel = """
        namespace $namespace

        use aws.protocols#$protocol

        @$protocol
        service $serviceName {
            version: "${TestDefault.MODEL_VERSION}",
            operations: [
                ${operations.joinToString(separator = ", ")}
            ]
        }
        
        $this
    """.trimIndent()

    return completeModel.asSmithyModel()
}

// Produce a GenerationContext given a model, it's expected namespace and service name.
fun Model.generateTestContext(namespace: String, serviceName: String): ProtocolGenerator.GenerationContext {
    val packageNode = Node.objectNode().withMember("name", Node.from(namespace))
        .withMember("version", Node.from(TestDefault.MODEL_VERSION))

    val settings = KotlinSettings.from(
        this,
        Node.objectNodeBuilder()
            .withMember("package", packageNode)
            .build()
    )
    val provider: SymbolProvider = KotlinCodegenPlugin.createSymbolProvider(this, rootNamespace = namespace, sdkId = serviceName)
    val service = this.expectShape<ServiceShape>("$namespace#$serviceName")
    val generator: ProtocolGenerator = MockHttpProtocolGenerator()
    val manifest = MockManifest()
    val delegator = KotlinDelegator(settings, this, manifest, provider)

    return ProtocolGenerator.GenerationContext(
        settings,
        this,
        service,
        provider,
        listOf(),
        generator.protocol,
        delegator
    )
}

enum class AwsProtocol(val annotation: String, val import: String) {
    RestJson("@restJson1", "aws.protocols#restJson1"),
    AwsJson1_1("@awsJson1_1", "aws.protocols#awsJson1_1")
}

// Generates the model header which by default conforms to the conventions defined for test models.
fun String.prependNamespaceAndService(
    version: String = TestDefault.SMITHY_IDL_VERSION,
    namespace: String = TestDefault.NAMESPACE,
    imports: List<String> = emptyList(),
    serviceName: String = TestDefault.SERVICE_NAME,
    protocol: AwsProtocol? = null,
    operations: List<String> = emptyList()
): String {
    val version = "\$version: \"$version\""
    val (modelProtocol, modelImports) = if (protocol == null) {
        "" to imports
    } else {
        protocol.annotation to imports + listOf(protocol.import)
    }

    val importExpr = modelImports.map { "use $it" }.joinToString(separator = "\n") { it }

    return (
        """
        $version
        namespace $namespace
        $importExpr
        $modelProtocol
        service $serviceName { 
            version: "${TestDefault.MODEL_VERSION}",
            operations: $operations
        }
        

        """.trimIndent() + this.trimIndent()
        ).also { println(it) }
}
