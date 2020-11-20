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

import org.junit.jupiter.api.Assertions
import software.amazon.smithy.build.MockManifest
import software.amazon.smithy.codegen.core.SymbolProvider
import software.amazon.smithy.kotlin.codegen.integration.DeserializeStructGenerator
import software.amazon.smithy.kotlin.codegen.integration.ProtocolGenerator
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.knowledge.HttpBinding
import software.amazon.smithy.model.knowledge.HttpBindingIndex
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.traits.TimestampFormatTrait
import java.net.URL

fun String.shouldSyntacticSanityCheck() {
    // sanity check since we are testing fragments
    var openBraces = 0
    var closedBraces = 0
    var openParens = 0
    var closedParens = 0
    this.forEach {
        when (it) {
            '{' -> openBraces++
            '}' -> closedBraces++
            '(' -> openParens++
            ')' -> closedParens++
        }
    }
    Assertions.assertEquals(openBraces, closedBraces, "unmatched open/closed braces:\n$this")
    Assertions.assertEquals(openParens, closedParens, "unmatched open/close parens:\n$this")
}

/**
 * Load and initialize a model from a Java resource URL
 */
fun URL.asSmithy(): Model =
        Model.assembler()
                .addImport(this)
                .discoverModels()
                .assemble()
                .unwrap()

/**
 * Container for type instances necessary for tests
 */
data class TestContext(
        val generationCtx: ProtocolGenerator.GenerationContext,
        val manifest: MockManifest,
        val generator: ProtocolGenerator
)

// Convenience function to retrieve a shape from a [TestContext]
fun TestContext.expectShape(shapeId: String): Shape =
        this.generationCtx.model.expectShape(ShapeId.from(shapeId))

/**
 * Initiate codegen for the model and produce a [TestContext].
 *
 * @param serviceShapeId the smithy Id for the service to codegen, defaults to "com.test#Example"
 */
fun Model.newTestContext(
        serviceShapeId: String = "com.test#Example",
        settings: KotlinSettings = this.defaultSettings(),
        generator: ProtocolGenerator = MockHttpProtocolGenerator()
): TestContext {
    val manifest = MockManifest()
    val provider: SymbolProvider = KotlinCodegenPlugin.createSymbolProvider(this, "test")
    val service = this.getShape(ShapeId.from(serviceShapeId)).get().asServiceShape().get()
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

private fun Model.defaultSettings(): KotlinSettings =
        KotlinSettings.from(
                this,
                Node.objectNodeBuilder()
                        .withMember("module", Node.from("test"))
                        .withMember("moduleVersion", Node.from("1.0.0"))
                        .build()
        )

// Execute the codegen and return the generated output
fun TestContext.render(
        shape: Shape,
        members: List<MemberShape> = defaultMembers(shape),
        renderFn: (List<MemberShape>, KotlinWriter) -> Unit
): String {
    val writer = KotlinWriter("test")
    renderFn(members, writer)
    return writer.toString()
}

// Retrieves Document members
private fun TestContext.defaultMembers(shape: Shape): List<MemberShape> {
    val bindingIndex = HttpBindingIndex.of(this.generationCtx.model)
    val responseBindings = bindingIndex.getResponseBindings(shape)

    return responseBindings.values
            .filter { it.location == HttpBinding.Location.DOCUMENT }
            .sortedBy { it.memberName }
            .map { it.member }
}


fun MockManifest.getTransformFileContents(filename: String): String {
    return expectFileString("src/main/kotlin/test/transform/$filename")
}
