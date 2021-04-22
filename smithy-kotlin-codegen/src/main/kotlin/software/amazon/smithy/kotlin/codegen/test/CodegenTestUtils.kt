package software.amazon.smithy.kotlin.codegen.test

import software.amazon.smithy.aws.traits.protocols.RestJson1Trait
import software.amazon.smithy.build.MockManifest
import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.kotlin.codegen.*
import software.amazon.smithy.kotlin.codegen.integration.*
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.knowledge.HttpBinding
import software.amazon.smithy.model.knowledge.HttpBindingIndex
import software.amazon.smithy.model.shapes.*
import software.amazon.smithy.model.traits.TimestampFormatTrait

/**
 * This file houses test classes and functions relating to the code generator (protocols, serializers, etc)
 *
 * Items contained here should be relatively high-level, utilizing all members of codegen classes, Smithy, and
 * anything else necessary for test functionality.
 */

/**
 * Container for type instances necessary for tests
 */
data class TestContext(
    val generationCtx: ProtocolGenerator.GenerationContext,
    val manifest: MockManifest,
    val generator: ProtocolGenerator
)

// Execute the codegen and return the generated output
fun testRender(
    members: List<MemberShape>,
    renderFn: (List<MemberShape>, KotlinWriter) -> Unit
): String {
    val writer = KotlinWriter(TestDefault.NAMESPACE)
    renderFn(members, writer)
    return writer.toString()
}

fun getRequestContentsForShape(model: Model, shapeId: String): String {
    val ctx = model.newTestContext()

    val op = ctx.generationCtx.model.expectShape(ShapeId.from(shapeId))
    return testRender(ctx.requestMembers(op)) { members, writer ->
        SerializeStructGenerator(
            ctx.generationCtx,
            members,
            writer,
            TimestampFormatTrait.Format.EPOCH_SECONDS
        ).render()
    }
}

fun getResponseContentsForShape(model: Model, shapeId: String): String {
    val ctx = model.newTestContext()
    val op = ctx.generationCtx.model.expectShape(ShapeId.from(shapeId))

    return testRender(ctx.responseMembers(op)) { members, writer ->
        DeserializeStructGenerator(
            ctx.generationCtx,
            members,
            writer,
            TimestampFormatTrait.Format.EPOCH_SECONDS
        ).render()
    }
}

fun getOperationStructRequestContentsForShape(model: Model, shapeId: String): String {
    val ctx = model.newTestContext()

    val testMembers = when (val shape = ctx.generationCtx.model.expectShape(ShapeId.from(shapeId))) {
        is OperationShape -> {
            val bindingIndex = HttpBindingIndex.of(ctx.generationCtx.model)
            val requestBindings = bindingIndex.getRequestBindings(shape)
            val unionShape = ctx.generationCtx.model.expectShape(requestBindings.values.first().member.target)
            unionShape.members().toList().sortedBy { it.memberName }
        }
        is StructureShape -> {
            shape.members().toList().sortedBy { it.memberName }
        }
        else -> throw RuntimeException("unknown conversion for $shapeId")
    }

    return testRender(testMembers) { members, writer ->
        SerializeUnionGenerator(
            ctx.generationCtx,
            members,
            writer,
            TimestampFormatTrait.Format.EPOCH_SECONDS
        ).render()
    }
}

// Retrieves Response Document members for HttpTrait-enabled protocols
fun TestContext.responseMembers(shape: Shape): List<MemberShape> {
    val bindingIndex = HttpBindingIndex.of(this.generationCtx.model)
    val responseBindings = bindingIndex.getResponseBindings(shape)

    return responseBindings.values
        .filter { it.location == HttpBinding.Location.DOCUMENT }
        .sortedBy { it.memberName }
        .map { it.member }
}

// Retrieves Request Document members for HttpTrait-enabled protocols
fun TestContext.requestMembers(shape: Shape): List<MemberShape> {
    val bindingIndex = HttpBindingIndex.of(this.generationCtx.model)
    val responseBindings = bindingIndex.getRequestBindings(shape)

    return responseBindings.values
        .filter { it.location == HttpBinding.Location.DOCUMENT }
        .sortedBy { it.memberName }
        .map { it.member }
}

// Assume a specific file path to retrieve a file from the manifest
fun MockManifest.getTransformFileContents(filename: String, packageNamespace: String = TestDefault.NAMESPACE): String {
    val packageNamespaceExpr = packageNamespace.replace('.', '/')
    return expectFileString("src/main/kotlin/$packageNamespaceExpr/transform/$filename")
}

fun TestContext.toGenerationContext(): GenerationContext =
    GenerationContext(generationCtx.model, generationCtx.symbolProvider, generationCtx.settings, generator)

fun <T : Shape> TestContext.toRenderingContext(writer: KotlinWriter, forShape: T? = null): RenderingContext<T> =
    toGenerationContext().toRenderingContext(writer, forShape)

class TestProtocolClientGenerator(
    ctx: ProtocolGenerator.GenerationContext,
    features: List<HttpFeature>,
    httpBindingResolver: HttpBindingResolver
) : HttpProtocolClientGenerator(ctx, features, httpBindingResolver) {
    override val serdeProviderSymbol: Symbol = buildSymbol {
        name = "JsonSerdeProvider"
        namespace(KotlinDependency.CLIENT_RT_SERDE_JSON)
    }
}

class MockHttpProtocolGenerator : HttpBindingProtocolGenerator() {
    override val defaultTimestampFormat: TimestampFormatTrait.Format = TimestampFormatTrait.Format.EPOCH_SECONDS
    override fun getProtocolHttpBindingResolver(ctx: ProtocolGenerator.GenerationContext): HttpBindingResolver =
        HttpTraitResolver(ctx, "application/json")

    override val protocol: ShapeId = RestJson1Trait.ID

    override fun generateProtocolUnitTests(ctx: ProtocolGenerator.GenerationContext) {}

    override fun getHttpProtocolClientGenerator(ctx: ProtocolGenerator.GenerationContext): HttpProtocolClientGenerator =
        TestProtocolClientGenerator(ctx, getHttpFeatures(ctx), getProtocolHttpBindingResolver(ctx))

    override fun generateSdkFieldDescriptor(
        ctx: ProtocolGenerator.GenerationContext,
        memberShape: MemberShape,
        writer: KotlinWriter,
        memberTargetShape: Shape?,
        namePostfix: String
    ) { }

    override fun generateSdkObjectDescriptorTraits(
        ctx: ProtocolGenerator.GenerationContext,
        objectShape: Shape,
        writer: KotlinWriter
    ) { }
}

fun codegenTestHarnessForModelSnippet(
    generator: ProtocolGenerator,
    namespace: String = TestDefault.NAMESPACE,
    serviceName: String = TestDefault.SERVICE_NAME,
    operations: List<String>,
    snippet: () -> String
): CodegenTestHarness {
    val protocol = generator.protocol.name
    val model = snippet().generateTestModel(protocol, namespace, serviceName, operations)
    val ctx = model.generateTestContext(namespace, serviceName)
    val manifest = ctx.delegator.fileManifest as MockManifest

    return CodegenTestHarness(ctx, manifest, generator, namespace, serviceName, protocol)
}

/**
 * Contains references to all types necessary to drive and validate codegen.
 */
data class CodegenTestHarness(
    val generationCtx: ProtocolGenerator.GenerationContext,
    val manifest: MockManifest,
    val generator: ProtocolGenerator,
    val namespace: String,
    val serviceName: String,
    val protocol: String
)

// Drive de/serializer codegen and return results in map indexed by filename.
fun CodegenTestHarness.generateDeSerializers(): Map<String, String> {
    generator.generateSerializers(generationCtx)
    generator.generateDeserializers(generationCtx)
    generationCtx.delegator.flushWriters()
    return manifest.files.associate { path -> path.fileName.toString() to manifest.expectFileString(path) }
}

fun KotlinCodegenPlugin.Companion.createSymbolProvider(model: Model, rootNamespace: String = TestDefault.NAMESPACE, sdkId: String = TestDefault.SERVICE_NAME) =
    KotlinCodegenPlugin.createSymbolProvider(model, rootNamespace, sdkId)

// Create and use a writer to drive codegen from a function taking a writer.
// Strip off comment and package preamble.
fun generateCode(generator: (KotlinWriter) -> Unit): String {
    val packageDeclaration = "some-unique-thing-that-will-never-be-codegened"
    val writer = KotlinWriter(packageDeclaration)
    generator.invoke(writer)
    val rawCodegen = writer.toString()
    return rawCodegen.substring(rawCodegen.indexOf(packageDeclaration) + packageDeclaration.length).trim()
}