/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.http.operation

import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.http.HttpCall
import aws.smithy.kotlin.runtime.http.request.HttpRequestBuilder
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import aws.smithy.kotlin.runtime.operation.ExecutionContext

/**
 * Implemented by types that know how to serialize to the HTTP protocol.
 */
@InternalApi
public sealed interface HttpSerializer<T> {

    /**
     * Serializer for streaming operations that need full control over serialization of the body
     */
    @InternalApi
    public interface Streaming<T> : HttpSerializer<T> {
        public suspend fun serialize(context: ExecutionContext, input: T): HttpRequestBuilder
    }

    /**
     * Serializer for non-streaming (simple) operations that don't need to ever suspend.
     */
    @InternalApi
    public interface NonStreaming<T> : HttpSerializer<T> {
        public fun serialize(context: ExecutionContext, input: T): HttpRequestBuilder
    }
}

/**
 * Implemented by types that know how to deserialize from the HTTP protocol.
 */
@InternalApi
public sealed interface HttpDeserializer<T> {

    /**
     * Deserializer for streaming operations that need full control over deserialization of the body
     */
    @InternalApi
    public interface Streaming<T> : HttpDeserializer<T> {
        public suspend fun deserialize(context: ExecutionContext, call: HttpCall): T
    }

    /**
     * Deserializer for non-streaming (simple) operations that don't need to ever suspend. These
     * operations are handed the full payload if it exists.
     */
    @InternalApi
    public interface NonStreaming<T> : HttpDeserializer<T> {
        public fun deserialize(context: ExecutionContext, call: HttpCall, payload: ByteArray?): T
    }
}

/**
 * Implemented by types that know how to serialize to the HTTP protocol.
 */
@Deprecated("use HttpSerializer.Streaming")
@InternalApi
public fun interface HttpSerialize<T> {
    public suspend fun serialize(context: ExecutionContext, input: T): HttpRequestBuilder
}

@Suppress("DEPRECATION")
private class LegacyHttpSerializeAdapter<T>(val serializer: HttpSerialize<T>) : HttpSerializer.Streaming<T> {
    override suspend fun serialize(context: ExecutionContext, input: T): HttpRequestBuilder =
        serializer.serialize(context, input)
}

@Suppress("DEPRECATION")
internal fun <T> HttpSerialize<T>.intoSerializer(): HttpSerializer<T> = LegacyHttpSerializeAdapter(this)

/**
 * Implemented by types that know how to deserialize from the HTTP protocol.
 */
@Deprecated("use HttpDeserializer.Streaming")
@InternalApi
public fun interface HttpDeserialize<T> {
    public suspend fun deserialize(context: ExecutionContext, call: HttpCall): T
}

@Suppress("DEPRECATION")
private class LegacyHttpDeserializeAdapter<T>(val deserializer: HttpDeserialize<T>) : HttpDeserializer.Streaming<T> {
    override suspend fun deserialize(context: ExecutionContext, call: HttpCall): T =
        deserializer.deserialize(context, call)
}

@Suppress("DEPRECATION")
internal fun <T> HttpDeserialize<T>.intoDeserializer(): HttpDeserializer<T> = LegacyHttpDeserializeAdapter(this)

/**
 * Convenience deserialize implementation for a type with no output type
 */
@Suppress("DEPRECATION")
@InternalApi
public object UnitDeserializer : HttpDeserialize<Unit> {
    override suspend fun deserialize(context: ExecutionContext, call: HttpCall) {}
}

/**
 * Convenience serialize implementation for a type with no input type
 */
@Suppress("DEPRECATION")
@InternalApi
public object UnitSerializer : HttpSerialize<Unit> {
    override suspend fun serialize(context: ExecutionContext, input: Unit): HttpRequestBuilder = HttpRequestBuilder()
}

/**
 * Convenience deserialize implementation that returns the response without modification
 */
@Suppress("DEPRECATION")
@InternalApi
public object IdentityDeserializer : HttpDeserialize<HttpResponse> {
    override suspend fun deserialize(context: ExecutionContext, call: HttpCall): HttpResponse = call.response
}
