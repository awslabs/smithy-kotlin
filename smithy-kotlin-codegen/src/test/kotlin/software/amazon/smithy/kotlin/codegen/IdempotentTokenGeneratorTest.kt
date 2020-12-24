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

import org.junit.jupiter.api.Test
import software.amazon.smithy.model.Model

// NOTE: protocol conformance is mostly handled by the protocol tests suite
class IdempotentTokenGeneratorTest {
    private val defaultModel: Model = javaClass.getResource("idempotent-token-test-model.smithy").asSmithy()

    private fun getTransformFileContents(filename: String): String {
        val (ctx, manifest, generator) = defaultModel.newTestContext()
        generator.generateSerializers(ctx)
        generator.generateDeserializers(ctx)
        ctx.delegator.flushWriters()
        return manifest.getTransformFileContents(filename)
    }

    @Test
    fun `it serializes operation payload inputs with idempotency token trait`() {
        val contents = getTransformFileContents("AllocateWidgetSerializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
class AllocateWidgetSerializer(val input: AllocateWidgetInput) : HttpSerialize {

    companion object {
        private val CLIENTTOKEN_DESCRIPTOR = SdkFieldDescriptor("clientToken", SerialKind.String)
        private val OBJ_DESCRIPTOR = SdkObjectDescriptor.build() {
            field(CLIENTTOKEN_DESCRIPTOR)
        }
    }

    override suspend fun serialize(builder: HttpRequestBuilder, serializationContext: SerializationContext) {
        builder.method = HttpMethod.POST

        builder.url {
            path = "/input/AllocateWidget"
        }

        builder.headers {
            setMissing("Content-Type", "application/json")
        }

        val serializer = serializationContext.serializationProvider()
        serializer.serializeStruct(OBJ_DESCRIPTOR) {
            input.clientToken?.let { field(CLIENTTOKEN_DESCRIPTOR, it) } ?: field(CLIENTTOKEN_DESCRIPTOR, serializationContext.idempotencyTokenProvider.generateToken())
        }

        builder.body = ByteArrayContent(serializer.toByteArray())
    }
}
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
    }

    @Test
    fun `it serializes operation query inputs with idempotency token trait`() {
        val contents = getTransformFileContents("AllocateWidgetQuerySerializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
class AllocateWidgetQuerySerializer(val input: AllocateWidgetInputQuery) : HttpSerialize {
    override suspend fun serialize(builder: HttpRequestBuilder, serializationContext: SerializationContext) {
        builder.method = HttpMethod.POST

        builder.url {
            path = "/input/AllocateWidgetQuery"
            parameters {
                append("clientToken", (input.clientToken ?: serializationContext.idempotencyTokenProvider.generateToken()))
            }
        }

        builder.headers {
            setMissing("Content-Type", "application/json")
        }

    }
}
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
    }

    @Test
    fun `it serializes operation header inputs with idempotency token trait`() {
        val contents = getTransformFileContents("AllocateWidgetHeaderSerializer.kt")
        contents.shouldSyntacticSanityCheck()
        val expectedContents = """
class AllocateWidgetHeaderSerializer(val input: AllocateWidgetInputHeader) : HttpSerialize {
    override suspend fun serialize(builder: HttpRequestBuilder, serializationContext: SerializationContext) {
        builder.method = HttpMethod.POST

        builder.url {
            path = "/input/AllocateWidgetHeader"
        }

        builder.headers {
            setMissing("Content-Type", "application/json")
            append("clientToken", (input.clientToken ?: serializationContext.idempotencyTokenProvider.generateToken()))
        }

    }
}
"""
        contents.shouldContainOnlyOnceWithDiff(expectedContents)
    }
}
