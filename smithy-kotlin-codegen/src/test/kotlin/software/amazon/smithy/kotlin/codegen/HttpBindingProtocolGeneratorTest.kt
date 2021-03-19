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

import io.kotest.matchers.string.shouldContainOnlyOnce
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.smithy.aws.traits.protocols.RestJson1Trait
import software.amazon.smithy.build.MockManifest
import software.amazon.smithy.kotlin.codegen.integration.*
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.traits.TimestampFormatTrait

class MockHttpProtocolGenerator : HttpBindingProtocolGenerator() {
    override val defaultTimestampFormat: TimestampFormatTrait.Format = TimestampFormatTrait.Format.EPOCH_SECONDS
    override fun getProtocolHttpBindingResolver(ctx: ProtocolGenerator.GenerationContext): HttpBindingResolver = HttpTraitResolver(ctx, "application/json")

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

// NOTE: protocol conformance is mostly handled by the protocol tests suite
class HttpBindingProtocolGeneratorTest {
    private val defaultModel = javaClass.getResource("http-binding-protocol-generator-test.smithy").asSmithy()
    private val modelPrefix = """
            namespace com.test

            use aws.protocols#restJson1

            @restJson1
            service Example {
                version: "1.0.0",
                operations: [
                    Foo,
                ]
            }

            @http(method: "POST", uri: "/foo-no-input")
            operation Foo {
                input: FooRequest
            }        
    """.trimIndent()

    private fun getTransformFileContents(filename: String, testModel: Model = defaultModel): String {
        val (ctx, manifest, generator) = testModel.newTestContext()
        generator.generateSerializers(ctx)
        generator.generateDeserializers(ctx)
        ctx.delegator.flushWriters()
        return getTransformFileContents(manifest, filename)
    }

    private fun getTransformFileContents(manifest: MockManifest, filename: String): String {
        return manifest.expectFileString("src/main/kotlin/test/transform/$filename")
    }

    @Test
    fun `it creates serialize transforms in correct package`() {
        val (ctx, manifest, generator) = defaultModel.newTestContext()
        generator.generateSerializers(ctx)
        ctx.delegator.flushWriters()
        assertTrue(manifest.hasFile("src/main/kotlin/test/transform/SmokeTestOperationSerializer.kt"))
    }

    @Test
    fun `it creates serialize transforms for nested structures`() {
        // test that a struct member of an input operation shape also gets a serializer
        val (ctx, manifest, generator) = defaultModel.newTestContext()
        generator.generateSerializers(ctx)
        ctx.delegator.flushWriters()
        assertTrue(manifest.hasFile("src/main/kotlin/test/transform/NestedDocumentSerializer.kt"))
        // these are non-top level shapes reachable from an operation input and thus require a serializer
        assertTrue(manifest.hasFile("src/main/kotlin/test/transform/Nested2DocumentSerializer.kt"))
        assertTrue(manifest.hasFile("src/main/kotlin/test/transform/Nested3DocumentSerializer.kt"))
        assertTrue(manifest.hasFile("src/main/kotlin/test/transform/Nested4DocumentSerializer.kt"))
    }

    @Test
    fun `it creates smoke test request serializer`() {
        val contents = getTransformFileContents("SmokeTestOperationSerializer.kt")
        contents.shouldSyntacticSanityCheck()
        val label1 = "\${input.label1}" // workaround for raw strings not being able to contain escapes
        val expectedContents = """
internal class SmokeTestOperationSerializer(): HttpSerialize<SmokeTestRequest> {

    companion object {
        private val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
            field(PAYLOAD1_DESCRIPTOR)
            field(PAYLOAD2_DESCRIPTOR)
            field(PAYLOAD3_DESCRIPTOR)
        }
    }

    override suspend fun serialize(context: ExecutionContext, input: SmokeTestRequest): HttpRequestBuilder {
        val builder = HttpRequestBuilder()
        builder.method = HttpMethod.POST

        builder.url {
            path = "/smoketest/$label1/foo"
            parameters {
                if (input.query1 != null) append("Query1", input.query1)
            }
        }

        builder.headers {
            if (input.header1?.isNotEmpty() == true) append("X-Header1", input.header1)
            if (input.header2?.isNotEmpty() == true) append("X-Header2", input.header2)
        }

        val serializer = context.serializer()
        serializer.serializeStruct(OBJ_DESCRIPTOR) {
            input.payload1?.let { field(PAYLOAD1_DESCRIPTOR, it) }
            input.payload2?.let { field(PAYLOAD2_DESCRIPTOR, it) }
            input.payload3?.let { field(PAYLOAD3_DESCRIPTOR, NestedDocumentSerializer(it)) }
        }

        builder.body = ByteArrayContent(serializer.toByteArray())
        if (builder.body !is HttpBody.Empty) {
            builder.headers["Content-Type"] = "application/json"
        }
        return builder
    }
}
"""
        // NOTE: SmokeTestRequest$payload3 is a struct itself, the Serializer interface handles this if the type
        // implements `SdkSerializable`
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
    }

    @Test
    fun `it serializes explicit string payloads`() {
        val contents = getTransformFileContents("ExplicitStringOperationSerializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
internal class ExplicitStringOperationSerializer(): HttpSerialize<ExplicitStringRequest> {
    override suspend fun serialize(context: ExecutionContext, input: ExplicitStringRequest): HttpRequestBuilder {
        val builder = HttpRequestBuilder()
        builder.method = HttpMethod.POST

        builder.url {
            path = "/explicit/string"
        }

        if (input.payload1 != null) {
            builder.body = ByteArrayContent(input.payload1.toByteArray())
        }
        if (builder.body !is HttpBody.Empty) {
            builder.headers["Content-Type"] = "text/plain"
        }
        return builder
    }
}
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
    }

    @Test
    fun `it serializes explicit blob payloads`() {
        val contents = getTransformFileContents("ExplicitBlobOperationSerializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
internal class ExplicitBlobOperationSerializer(): HttpSerialize<ExplicitBlobRequest> {
    override suspend fun serialize(context: ExecutionContext, input: ExplicitBlobRequest): HttpRequestBuilder {
        val builder = HttpRequestBuilder()
        builder.method = HttpMethod.POST

        builder.url {
            path = "/explicit/blob"
        }

        if (input.payload1 != null) {
            builder.body = ByteArrayContent(input.payload1)
        }
        if (builder.body !is HttpBody.Empty) {
            builder.headers["Content-Type"] = "application/octet-stream"
        }
        return builder
    }
}
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
    }

    @Test
    fun `it serializes explicit streaming blob payloads`() {
        val contents = getTransformFileContents("ExplicitBlobStreamOperationSerializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
internal class ExplicitBlobStreamOperationSerializer(): HttpSerialize<ExplicitBlobStreamRequest> {
    override suspend fun serialize(context: ExecutionContext, input: ExplicitBlobStreamRequest): HttpRequestBuilder {
        val builder = HttpRequestBuilder()
        builder.method = HttpMethod.POST

        builder.url {
            path = "/explicit/blobstream"
        }

        if (input.payload1 != null) {
            builder.body = input.payload1.toHttpBody() ?: HttpBody.Empty
        }
        if (builder.body !is HttpBody.Empty) {
            builder.headers["Content-Type"] = "application/octet-stream"
        }
        return builder
    }
}
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
    }

    @Test
    fun `it serializes explicit struct payloads`() {
        val contents = getTransformFileContents("ExplicitStructOperationSerializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
internal class ExplicitStructOperationSerializer(): HttpSerialize<ExplicitStructRequest> {
    override suspend fun serialize(context: ExecutionContext, input: ExplicitStructRequest): HttpRequestBuilder {
        val builder = HttpRequestBuilder()
        builder.method = HttpMethod.POST

        builder.url {
            path = "/explicit/struct"
        }

        if (input.payload1 != null) {
            val serializer = context.serializer()
            Nested2DocumentSerializer(input.payload1).serialize(serializer)
            builder.body = ByteArrayContent(serializer.toByteArray())
        }
        if (builder.body !is HttpBody.Empty) {
            builder.headers["Content-Type"] = "application/json"
        }
        return builder
    }
}
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
    }

    @Test
    fun `it serializes nested documents with aggregate shapes`() {
        // non operational input (nested member somewhere in the graph) that has a list/map shape
        val contents = getTransformFileContents("Nested4DocumentSerializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
internal class Nested4DocumentSerializer(val input: Nested4) : SdkSerializable {

    companion object {
        private val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
            field(INTLIST_DESCRIPTOR)
            field(INTMAP_DESCRIPTOR)
            field(MEMBER1_DESCRIPTOR)
        }
    }

    override fun serialize(serializer: Serializer) {
        serializer.serializeStruct(OBJ_DESCRIPTOR) {
            if (input.intList != null) {
                listField(INTLIST_DESCRIPTOR) {
                    for (el0 in input.intList) {
                        serializeInt(el0)
                    }
                }
            }
            if (input.intMap != null) {
                mapField(INTMAP_DESCRIPTOR) {
                    input.intMap.forEach { (key, value) -> entry(key, value) }
                }
            }
            input.member1?.let { field(MEMBER1_DESCRIPTOR, it) }
        }
    }
}
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
        contents.shouldContainOnlyOnce("import test.model.Nested4")
    }

    @Test
    fun `it serializes nested documents with struct members`() {
        // non operational input (nested member somewhere in the graph) that has another non-operational struct as a member
        val contents = getTransformFileContents("Nested3DocumentSerializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
internal class Nested3DocumentSerializer(val input: Nested3) : SdkSerializable {

    companion object {
        private val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
            field(MEMBER1_DESCRIPTOR)
            field(MEMBER2_DESCRIPTOR)
            field(MEMBER3_DESCRIPTOR)
        }
    }

    override fun serialize(serializer: Serializer) {
        serializer.serializeStruct(OBJ_DESCRIPTOR) {
            input.member1?.let { field(MEMBER1_DESCRIPTOR, it) }
            input.member2?.let { field(MEMBER2_DESCRIPTOR, it) }
            input.member3?.let { field(MEMBER3_DESCRIPTOR, Nested4DocumentSerializer(it)) }
        }
    }
}
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
        contents.shouldContainOnlyOnce("import test.model.Nested3")
    }

    @Test
    fun `it serializes documents with union members`() {
        // non operational input (nested member somewhere in the graph) that has another non-operational struct as a member
        val contents = getTransformFileContents("UnionInputOperationSerializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
internal class UnionInputOperationSerializer(): HttpSerialize<UnionRequest> {

    companion object {
        private val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
            field(PAYLOADUNION_DESCRIPTOR)
        }
    }

    override suspend fun serialize(context: ExecutionContext, input: UnionRequest): HttpRequestBuilder {
        val builder = HttpRequestBuilder()
        builder.method = HttpMethod.POST

        builder.url {
            path = "/input/union"
        }

        val serializer = context.serializer()
        serializer.serializeStruct(OBJ_DESCRIPTOR) {
            input.payloadUnion?.let { field(PAYLOADUNION_DESCRIPTOR, MyUnionDocumentSerializer(it)) }
        }

        builder.body = ByteArrayContent(serializer.toByteArray())
        if (builder.body !is HttpBody.Empty) {
            builder.headers["Content-Type"] = "application/json"
        }
        return builder
    }
}
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
        contents.shouldContainOnlyOnce("import test.model.UnionRequest")
    }

    @Test
    fun `it serializes documents with union members containing collections of structures`() {
        val model = (
            modelPrefix + """            
            structure FooRequest { 
                payload: FooUnion
            }
            
            union FooUnion {
                structList: BarList
            }
            
            list BarList {
                member: BarStruct
            }
            
            structure BarStruct {
                someValue: FooUnion
            }
        """
            ).asSmithyModel()
        val contents = getTransformFileContents("FooUnionDocumentSerializer.kt", model)
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
            internal class FooUnionDocumentSerializer(val input: FooUnion) : SdkSerializable {
            
                companion object {
                    private val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
                        field(STRUCTLIST_DESCRIPTOR)
                    }
                }
            
                override fun serialize(serializer: Serializer) {
                    serializer.serializeStruct(OBJ_DESCRIPTOR) {
                        when (input) {
                            is FooUnion.StructList -> {
                                listField(STRUCTLIST_DESCRIPTOR) {
                                    for (el0 in input.value) {
                                        serializeSdkSerializable(BarStructDocumentSerializer(el0))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
    }

    @Test
    fun `it deserializes documents with union members`() {
        // non operational input (nested member somewhere in the graph) that has another non-operational struct as a member
        val contents = getTransformFileContents("UnionOutputOperationDeserializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
internal class UnionOutputOperationDeserializer(): HttpDeserialize<UnionRequest> {

    companion object {
        private val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
            field(PAYLOADUNION_DESCRIPTOR)
        }
    }

    override suspend fun deserialize(context: ExecutionContext, response: HttpResponse): UnionRequest {
        val builder = UnionRequest.dslBuilder()

        val payload = response.body.readAll()
        if (payload != null) {
            val deserializer = context.deserializer(payload)
            deserializer.deserializeStruct(OBJ_DESCRIPTOR) {
                loop@while (true) {
                    when (findNextFieldIndex()) {
                        PAYLOADUNION_DESCRIPTOR.index -> builder.payloadUnion = MyUnionDocumentDeserializer().deserialize(deserializer)
                        null -> break@loop
                        else -> skipValue()
                    }
                }
            }
        }
        return builder.build()
    }
}
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
        contents.shouldContainOnlyOnce("import test.model.UnionRequest")
    }

    @Test
    fun `it deserializes documents with aggregate union members`() {
        // non operational input (nested member somewhere in the graph) that has another non-operational struct as a member
        val contents = getTransformFileContents("UnionAggregateOutputOperationDeserializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
internal class UnionAggregateOutputOperationDeserializer(): HttpDeserialize<UnionAggregateRequest> {

    companion object {
        private val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
            field(PAYLOADAGGREGATEUNION_DESCRIPTOR)
        }
    }

    override suspend fun deserialize(context: ExecutionContext, response: HttpResponse): UnionAggregateRequest {
        val builder = UnionAggregateRequest.dslBuilder()

        val payload = response.body.readAll()
        if (payload != null) {
            val deserializer = context.deserializer(payload)
            deserializer.deserializeStruct(OBJ_DESCRIPTOR) {
                loop@while (true) {
                    when (findNextFieldIndex()) {
                        PAYLOADAGGREGATEUNION_DESCRIPTOR.index -> builder.payloadAggregateUnion = MyAggregateUnionDocumentDeserializer().deserialize(deserializer)
                        null -> break@loop
                        else -> skipValue()
                    }
                }
            }
        }
        return builder.build()
    }
}
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
        contents.shouldContainOnlyOnce("import test.model.UnionAggregateRequest")
    }

    @Test
    fun `it generates union member serializers`() {
        // non operational input (nested member somewhere in the graph) that has another non-operational struct as a member
        val contents = getTransformFileContents("MyUnionDocumentSerializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
internal class MyUnionDocumentSerializer(val input: MyUnion) : SdkSerializable {

    companion object {
        private val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
            field(I32_DESCRIPTOR)
            field(STRINGA_DESCRIPTOR)
        }
    }

    override fun serialize(serializer: Serializer) {
        serializer.serializeStruct(OBJ_DESCRIPTOR) {
            when (input) {
                is MyUnion.I32 -> field(I32_DESCRIPTOR, input.value)
                is MyUnion.StringA -> field(STRINGA_DESCRIPTOR, input.value)
            }
        }
    }
}
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
        contents.shouldContainOnlyOnce("import test.model.MyUnion")
    }

    @Test
    fun `it generates union member deserializers`() {
        // non operational input (nested member somewhere in the graph) that has another non-operational struct as a member
        val contents = getTransformFileContents("MyUnionDocumentDeserializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
    companion object {
        private val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
            field(I32_DESCRIPTOR)
            field(STRINGA_DESCRIPTOR)
        }
    }

    suspend fun deserialize(deserializer: Deserializer): MyUnion {
        var value: MyUnion? = null
        deserializer.deserializeStruct(OBJ_DESCRIPTOR) {
            when(findNextFieldIndex()) {
                I32_DESCRIPTOR.index -> value = MyUnion.I32(deserializeInt())
                STRINGA_DESCRIPTOR.index -> value = MyUnion.StringA(deserializeString())
                else -> value = MyUnion.SdkUnknown.also { skipValue() }
            }
        }
        return value ?: throw DeserializationException("Deserialized value unexpectedly null: MyUnion")
    }
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
        contents.shouldContainOnlyOnce("import test.model.MyUnion")
    }

    @Test
    fun `it generates serializer for shape reachable only through map`() {
        val (ctx, manifest, generator) = defaultModel.newTestContext()
        generator.generateSerializers(ctx)
        ctx.delegator.flushWriters()
        // serializer should exist for the map value `ReachableOnlyThroughMap`
        assertTrue(manifest.hasFile("src/main/kotlin/test/transform/ReachableOnlyThroughMapDocumentSerializer.kt"))
        val contents = getTransformFileContents(manifest, "MapInputOperationSerializer.kt")
        contents.shouldContainOnlyOnce("import test.model.MapInputRequest")
    }

    @Test
    fun `it serializes operation inputs with enums`() {
        val contents = getTransformFileContents("EnumInputOperationSerializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
internal class EnumInputOperationSerializer(): HttpSerialize<EnumInputRequest> {

    companion object {
        private val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
            field(NESTEDWITHENUM_DESCRIPTOR)
        }
    }

    override suspend fun serialize(context: ExecutionContext, input: EnumInputRequest): HttpRequestBuilder {
        val builder = HttpRequestBuilder()
        builder.method = HttpMethod.POST

        builder.url {
            path = "/input/enum"
        }

        builder.headers {
            if (input.enumHeader != null) append("X-EnumHeader", input.enumHeader.value)
        }

        val serializer = context.serializer()
        serializer.serializeStruct(OBJ_DESCRIPTOR) {
            input.nestedWithEnum?.let { field(NESTEDWITHENUM_DESCRIPTOR, NestedEnumDocumentSerializer(it)) }
        }

        builder.body = ByteArrayContent(serializer.toByteArray())
        if (builder.body !is HttpBody.Empty) {
            builder.headers["Content-Type"] = "application/json"
        }
        return builder
    }
}
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
    }

    @Test
    fun `it serializes operation inputs with timestamps`() {
        val contents = getTransformFileContents("TimestampInputOperationSerializer.kt")
        contents.shouldSyntacticSanityCheck()
        val tsLabel = "\${input.tsLabel?.format(TimestampFormat.ISO_8601)}" // workaround for raw strings not being able to contain escapes
        val expectedContents = """
internal class TimestampInputOperationSerializer(): HttpSerialize<TimestampInputRequest> {

    companion object {
        private val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
            field(DATETIME_DESCRIPTOR)
            field(EPOCHSECONDS_DESCRIPTOR)
            field(HTTPDATE_DESCRIPTOR)
            field(NORMAL_DESCRIPTOR)
            field(TIMESTAMPLIST_DESCRIPTOR)
        }
    }

    override suspend fun serialize(context: ExecutionContext, input: TimestampInputRequest): HttpRequestBuilder {
        val builder = HttpRequestBuilder()
        builder.method = HttpMethod.POST

        builder.url {
            path = "/input/timestamp/$tsLabel"
            parameters {
                if (input.queryTimestamp != null) append("qtime", input.queryTimestamp.format(TimestampFormat.ISO_8601))
                if (input.queryTimestampList?.isNotEmpty() == true) appendAll("qtimeList", input.queryTimestampList.map { it.format(TimestampFormat.ISO_8601) })
            }
        }

        builder.headers {
            if (input.headerDateTime != null) append("X-DateTime", input.headerDateTime.format(TimestampFormat.ISO_8601))
            if (input.headerEpoch != null) append("X-Epoch", input.headerEpoch.format(TimestampFormat.EPOCH_SECONDS))
            if (input.headerHttpDate != null) append("X-Date", input.headerHttpDate.format(TimestampFormat.RFC_5322))
        }

        val serializer = context.serializer()
        serializer.serializeStruct(OBJ_DESCRIPTOR) {
            input.dateTime?.let { field(DATETIME_DESCRIPTOR, it.format(TimestampFormat.ISO_8601)) }
            input.epochSeconds?.let { rawField(EPOCHSECONDS_DESCRIPTOR, it.format(TimestampFormat.EPOCH_SECONDS)) }
            input.httpDate?.let { field(HTTPDATE_DESCRIPTOR, it.format(TimestampFormat.RFC_5322)) }
            input.normal?.let { rawField(NORMAL_DESCRIPTOR, it.format(TimestampFormat.EPOCH_SECONDS)) }
            if (input.timestampList != null) {
                listField(TIMESTAMPLIST_DESCRIPTOR) {
                    for (el0 in input.timestampList) {
                        serializeRaw(el0.format(TimestampFormat.EPOCH_SECONDS))
                    }
                }
            }
        }

        builder.body = ByteArrayContent(serializer.toByteArray())
        if (builder.body !is HttpBody.Empty) {
            builder.headers["Content-Type"] = "application/json"
        }
        return builder
    }
}
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
        contents.shouldContainOnlyOnce("import software.aws.clientrt.time.TimestampFormat")
    }

    @Test
    fun `it creates blob input request serializer`() {
        // base64 encoding is protocol dependent. The mock protocol generator is based on
        // json protocol though which does encode to base64
        val contents = getTransformFileContents("BlobInputOperationSerializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
internal class BlobInputOperationSerializer(): HttpSerialize<BlobInputRequest> {

    companion object {
        private val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
            field(PAYLOADBLOB_DESCRIPTOR)
        }
    }

    override suspend fun serialize(context: ExecutionContext, input: BlobInputRequest): HttpRequestBuilder {
        val builder = HttpRequestBuilder()
        builder.method = HttpMethod.POST

        builder.url {
            path = "/input/blob"
        }

        builder.headers {
            if (input.headerMediaType?.isNotEmpty() == true) append("X-Blob", input.headerMediaType.encodeBase64())
        }

        val serializer = context.serializer()
        serializer.serializeStruct(OBJ_DESCRIPTOR) {
            input.payloadBlob?.let { field(PAYLOADBLOB_DESCRIPTOR, it.encodeBase64String()) }
        }

        builder.body = ByteArrayContent(serializer.toByteArray())
        if (builder.body !is HttpBody.Empty) {
            builder.headers["Content-Type"] = "application/json"
        }
        return builder
    }
}
"""
        // NOTE: SmokeTestRequest$payload3 is a struct itself, the Serializer interface handles this if the type
        // implements `SdkSerializable`
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
    }

    @Test
    fun `it handles query string literals`() {
        val contents = getTransformFileContents("ConstantQueryStringOperationSerializer.kt")
        contents.shouldSyntacticSanityCheck()
        val label1 = "\${input.hello}" // workaround for raw strings not being able to contain escapes
        val expectedContents = """
internal class ConstantQueryStringOperationSerializer(): HttpSerialize<ConstantQueryStringInput> {
    override suspend fun serialize(context: ExecutionContext, input: ConstantQueryStringInput): HttpRequestBuilder {
        val builder = HttpRequestBuilder()
        builder.method = HttpMethod.GET

        builder.url {
            path = "/ConstantQueryString/$label1"
            parameters {
                append("foo", "bar")
                append("hello", "")
            }
        }

        return builder
    }
}
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
    }

    @Test
    fun `it creates smoke test response deserializer`() {
        val contents = getTransformFileContents("SmokeTestOperationDeserializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
internal class SmokeTestOperationDeserializer(): HttpDeserialize<SmokeTestResponse> {

    companion object {
        private val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
            field(PAYLOAD1_DESCRIPTOR)
            field(PAYLOAD2_DESCRIPTOR)
            field(PAYLOAD3_DESCRIPTOR)
            field(PAYLOAD4_DESCRIPTOR)
        }
    }

    override suspend fun deserialize(context: ExecutionContext, response: HttpResponse): SmokeTestResponse {
        val builder = SmokeTestResponse.dslBuilder()

        builder.intHeader = response.headers["X-Header2"]?.toInt()
        builder.strHeader = response.headers["X-Header1"]
        builder.tsListHeader = response.headers.getAll("X-Header3")?.flatMap(::splitHttpDateHeaderListValues)?.map { Instant.fromRfc5322(it) }

        val payload = response.body.readAll()
        if (payload != null) {
            val deserializer = context.deserializer(payload)
            deserializer.deserializeStruct(OBJ_DESCRIPTOR) {
                loop@while (true) {
                    when (findNextFieldIndex()) {
                        PAYLOAD1_DESCRIPTOR.index -> builder.payload1 = deserializeString()
                        PAYLOAD2_DESCRIPTOR.index -> builder.payload2 = deserializeInt()
                        PAYLOAD3_DESCRIPTOR.index -> builder.payload3 = NestedDocumentDeserializer().deserialize(deserializer)
                        PAYLOAD4_DESCRIPTOR.index -> builder.payload4 = deserializeString().let { Instant.fromIso8601(it) }
                        null -> break@loop
                        else -> skipValue()
                    }
                }
            }
        }
        return builder.build()
    }
}
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
    }

    @Test
    fun `it deserializes prefix headers`() {
        val contents = getTransformFileContents("PrefixHeadersOperationDeserializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
        val keysForMember1 = response.headers.names().filter { it.startsWith("X-Foo-") }
        if (keysForMember1.isNotEmpty()) {
            val map = mutableMapOf<String, String>()
            for (hdrKey in keysForMember1) {
                val el = response.headers[hdrKey] ?: continue
                val key = hdrKey.removePrefix("X-Foo-")
                map[key] = el
            }
            builder.member1 = map
        }
"""
        contents.shouldContainOnlyOnce(expectedContents)
    }

    @Test
    fun `it deserializes primitive headers`() {
        val contents = getTransformFileContents("PrimitiveShapesOperationOperationDeserializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
        builder.hBool = response.headers["X-d"]?.toBoolean() ?: false
        builder.hFloat = response.headers["X-c"]?.toFloat() ?: 0.0f
        builder.hInt = response.headers["X-a"]?.toInt() ?: 0
        builder.hLong = response.headers["X-b"]?.toLong() ?: 0L
        builder.hRequiredInt = response.headers["X-required"]?.toInt() ?: 0
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
    }

    @Test
    fun `it deserializes explicit string payloads`() {
        val contents = getTransformFileContents("ExplicitStringOperationDeserializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
        val contents = response.body.readAll()?.decodeToString()
        builder.payload1 = contents
"""
        contents.shouldContainOnlyOnce(expectedContents)
    }

    @Test
    fun `it deserializes explicit blob payloads`() {
        val contents = getTransformFileContents("ExplicitBlobOperationDeserializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
        builder.payload1 = response.body.readAll()
"""
        contents.shouldContainOnlyOnce(expectedContents)
    }

    @Test
    fun `it deserializes explicit streaming blob payloads`() {
        val contents = getTransformFileContents("ExplicitBlobStreamOperationDeserializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
        builder.payload1 = response.body.toByteStream()
"""
        contents.shouldContainOnlyOnce(expectedContents)
    }

    @Test
    fun `it deserializes explicit struct payloads`() {
        val contents = getTransformFileContents("ExplicitStructOperationDeserializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
        val payload = response.body.readAll()
        if (payload != null) {
            val deserializer = context.deserializer(payload)
            builder.payload1 = Nested2DocumentDeserializer().deserialize(deserializer)
        }
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
    }

    @Test
    fun `it deserializes nested documents with struct members`() {
        // non operational output (nested member somewhere in the graph) that has another non-operational struct as a member
        val contents = getTransformFileContents("Nested3DocumentDeserializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
internal class Nested3DocumentDeserializer {

    companion object {
        private val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
            field(MEMBER1_DESCRIPTOR)
            field(MEMBER2_DESCRIPTOR)
            field(MEMBER3_DESCRIPTOR)
        }
    }

    suspend fun deserialize(deserializer: Deserializer): Nested3 {
        val builder = Nested3.dslBuilder()
        deserializer.deserializeStruct(OBJ_DESCRIPTOR) {
            loop@while (true) {
                when (findNextFieldIndex()) {
                    MEMBER1_DESCRIPTOR.index -> builder.member1 = deserializeString()
                    MEMBER2_DESCRIPTOR.index -> builder.member2 = deserializeString()
                    MEMBER3_DESCRIPTOR.index -> builder.member3 = Nested4DocumentDeserializer().deserialize(deserializer)
                    null -> break@loop
                    else -> skipValue()
                }
            }
        }
        return builder.build()
    }
}
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
        contents.shouldContainOnlyOnce("import test.model.Nested3")
    }

    @Test
    fun `it creates deserialize transforms for errors`() {
        // test that a struct member of an input operation shape also gets a serializer
        val (ctx, manifest, generator) = defaultModel.newTestContext()
        generator.generateDeserializers(ctx)
        ctx.delegator.flushWriters()
        assertTrue(manifest.hasFile("src/main/kotlin/test/transform/SmokeTestErrorDeserializer.kt"))
        assertTrue(manifest.hasFile("src/main/kotlin/test/transform/NestedErrorDataDocumentDeserializer.kt"))
    }

    @Test
    fun `it creates map of lists serializer`() {
        val mapModel = javaClass.getResource("http-binding-map-model.smithy").asSmithy()

        val contents = getTransformFileContents("MapInputOperationSerializer.kt", mapModel)
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
internal class MapInputOperationSerializer(): HttpSerialize<MapInputRequest> {

    companion object {
        private val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
            field(MAPOFLISTS_DESCRIPTOR)
        }
    }

    override suspend fun serialize(context: ExecutionContext, input: MapInputRequest): HttpRequestBuilder {
        val builder = HttpRequestBuilder()
        builder.method = HttpMethod.POST

        builder.url {
            path = "/input/map"
        }

        val serializer = context.serializer()
        serializer.serializeStruct(OBJ_DESCRIPTOR) {
            if (input.mapOfLists != null) {
                mapField(MAPOFLISTS_DESCRIPTOR) {
                    input.mapOfLists.forEach { (key, value) -> listEntry(key, MAPOFLISTS_C0_DESCRIPTOR) {
                        for (el1 in value) {
                            serializeInt(el1)
                        }
                    }}
                }
            }
        }

        builder.body = ByteArrayContent(serializer.toByteArray())
        if (builder.body !is HttpBody.Empty) {
            builder.headers["Content-Type"] = "application/json"
        }
        return builder
    }
}
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
    }

    @Test
    fun `it leaves off content-type`() {
        // GET/HEAD/TRACE/OPTIONS/CONNECT shouldn't specify content-type
        val contents = getTransformFileContents("ConstantQueryStringOperationSerializer.kt")
        contents.shouldNotContain("Content-Type")
    }
}
