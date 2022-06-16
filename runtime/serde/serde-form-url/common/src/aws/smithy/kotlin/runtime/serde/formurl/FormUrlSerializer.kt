/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package aws.smithy.kotlin.runtime.serde.formurl

import aws.smithy.kotlin.runtime.io.SdkByteBuffer
import aws.smithy.kotlin.runtime.io.bytes
import aws.smithy.kotlin.runtime.io.write
import aws.smithy.kotlin.runtime.serde.*
import aws.smithy.kotlin.runtime.smithy.Document
import aws.smithy.kotlin.runtime.time.Instant
import aws.smithy.kotlin.runtime.time.TimestampFormat
import aws.smithy.kotlin.runtime.util.text.urlEncodeComponent
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

fun FormUrlSerializer(): Serializer = FormUrlSerializer(SdkByteBuffer(256u))

private class FormUrlSerializer(
    val buffer: SdkByteBuffer,
    val prefix: String = ""
) : Serializer {

    override fun beginStruct(descriptor: SdkFieldDescriptor): StructSerializer =
        FormUrlStructSerializer(this, descriptor, prefix)

    override fun beginList(descriptor: SdkFieldDescriptor): ListSerializer =
        FormUrlListSerializer(this, descriptor)

    override fun beginMap(descriptor: SdkFieldDescriptor): MapSerializer =
        FormUrlMapSerializer(this, descriptor)

    override fun toByteArray(): ByteArray = buffer.bytes()

    private fun write(block: SdkByteBuffer.() -> Unit) {
        buffer.apply(block)
    }

    private fun write(value: String) = write { write(value.urlEncodeComponent()) }

    override fun serializeBoolean(value: Boolean) = write("$value")
    override fun serializeByte(value: Byte) = write { commonWriteNumber(value) }
    override fun serializeChar(value: Char) = write(value.toString())
    override fun serializeShort(value: Short) = write { commonWriteNumber(value) }
    override fun serializeInt(value: Int) = write { commonWriteNumber(value) }
    override fun serializeLong(value: Long) = write { commonWriteNumber(value) }
    override fun serializeFloat(value: Float) = write { commonWriteNumber(value) }
    override fun serializeDouble(value: Double) = write { commonWriteNumber(value) }
    override fun serializeString(value: String) = write(value)

    override fun serializeInstant(value: Instant, format: TimestampFormat) {
        serializeString(value.format(format))
    }

    override fun serializeSdkSerializable(value: SdkSerializable) {
        value.serialize(this)
    }

    override fun serializeNull() {
        throw SerializationException("null values not supported by form-url serializer")
    }
}

private class FormUrlStructSerializer(
    private val parent: FormUrlSerializer,
    private val structDescriptor: SdkFieldDescriptor,
    // field prefix (e.g. nested structures, list elements, etc)
    private val prefix: String
) : StructSerializer, PrimitiveSerializer by parent {
    private val buffer
        get() = parent.buffer

    init {
        structDescriptor.traits.mapNotNull { it as? QueryLiteral }
            .forEach { literal ->
                writeField(literal.toDescriptor()) {
                    serializeString(literal.value)
                }
            }
    }

    private fun writeField(descriptor: SdkFieldDescriptor, block: () -> Unit) {
        if (buffer.writePosition > 0u) {
            buffer.write("&")
        }
        if (prefix.isNotBlank()) buffer.write(prefix)
        buffer.write(descriptor.serialName)
        buffer.write("=")
        block()
    }

    override fun field(descriptor: SdkFieldDescriptor, value: Boolean) = writeField(descriptor) {
        serializeBoolean(value)
    }

    override fun field(descriptor: SdkFieldDescriptor, value: Byte) = writeField(descriptor) {
        serializeByte(value)
    }

    override fun field(descriptor: SdkFieldDescriptor, value: Short) = writeField(descriptor) {
        serializeShort(value)
    }

    override fun field(descriptor: SdkFieldDescriptor, value: Char) = writeField(descriptor) {
        serializeChar(value)
    }

    override fun field(descriptor: SdkFieldDescriptor, value: Int) = writeField(descriptor) {
        serializeInt(value)
    }

    override fun field(descriptor: SdkFieldDescriptor, value: Long) = writeField(descriptor) {
        serializeLong(value)
    }

    override fun field(descriptor: SdkFieldDescriptor, value: Float) = writeField(descriptor) {
        serializeFloat(value)
    }

    override fun field(descriptor: SdkFieldDescriptor, value: Double) = writeField(descriptor) {
        serializeDouble(value)
    }

    override fun field(descriptor: SdkFieldDescriptor, value: String) = writeField(descriptor) {
        serializeString(value)
    }

    override fun field(descriptor: SdkFieldDescriptor, value: Instant, format: TimestampFormat) = writeField(descriptor) {
        serializeInstant(value, format)
    }

    override fun field(descriptor: SdkFieldDescriptor, value: Document?) {
        throw SerializationException(
            "cannot serialize field ${descriptor.serialName}; Document type is not supported by form-url encoding"
        )
    }

    override fun field(descriptor: SdkFieldDescriptor, value: SdkSerializable) {
        val nestedPrefix = "${prefix}${descriptor.serialName}."
        // prepend the current prefix if one exists (e.g. deeply nested structures)
        value.serialize(
            FormUrlSerializer(buffer, nestedPrefix)
        )
    }

    override fun structField(descriptor: SdkFieldDescriptor, block: StructSerializer.() -> Unit) {
        // FIXME - do we even use this function in any of the formats? It seems like we go through `field(.., SdkSerializable)` ??
        // https://github.com/awslabs/smithy-kotlin/issues/314
        TODO("Not yet implemented")
    }

    override fun listField(descriptor: SdkFieldDescriptor, block: ListSerializer.() -> Unit) {
        val childDescriptor = descriptor.copyWithNewSerialName("${prefix}${descriptor.serialName}")
        FormUrlListSerializer(parent, childDescriptor).apply(block)
    }

    override fun mapField(descriptor: SdkFieldDescriptor, block: MapSerializer.() -> Unit) {
        val childDescriptor = descriptor.copyWithNewSerialName("${prefix}${descriptor.serialName}")
        FormUrlMapSerializer(parent, childDescriptor).apply(block)
    }

    override fun nullField(descriptor: SdkFieldDescriptor) {
        // null not supported
    }

    override fun endStruct() {
        // no terminating tokens for a struct
    }
}

private class FormUrlListSerializer(
    parent: FormUrlSerializer,
    private val descriptor: SdkFieldDescriptor
) : ListSerializer {
    private val buffer = parent.buffer
    private var idx = 0

    private fun prefix(): String = when {
        descriptor.hasTrait<FormUrlFlattened>() -> "${descriptor.serialName}.$idx"
        else -> {
            val memberName = descriptor.findTrait<FormUrlCollectionName>() ?: FormUrlCollectionName.Default
            "${descriptor.serialName}.${memberName.member}.$idx"
        }
    }

    private fun writePrefixed(block: SdkByteBuffer.() -> Unit) {
        idx++
        if (buffer.writePosition > 0u) buffer.write("&")
        buffer.write(prefix())
        buffer.write("=")
        buffer.apply(block)
    }

    override fun endList() {}
    override fun serializeBoolean(value: Boolean) = writePrefixed { write("$value") }
    override fun serializeChar(value: Char) = writePrefixed { write(value.toString()) }
    override fun serializeByte(value: Byte) = writePrefixed { commonWriteNumber(value) }
    override fun serializeShort(value: Short) = writePrefixed { commonWriteNumber(value) }
    override fun serializeInt(value: Int) = writePrefixed { commonWriteNumber(value) }
    override fun serializeLong(value: Long) = writePrefixed { commonWriteNumber(value) }
    override fun serializeFloat(value: Float) = writePrefixed { commonWriteNumber(value) }
    override fun serializeDouble(value: Double) = writePrefixed { commonWriteNumber(value) }
    override fun serializeString(value: String) = writePrefixed { write(value.urlEncodeComponent()) }
    override fun serializeInstant(value: Instant, format: TimestampFormat) = writePrefixed { write(value.format(format)) }

    override fun serializeSdkSerializable(value: SdkSerializable) {
        idx++
        val nestedPrefix = prefix() + "."
        value.serialize(FormUrlSerializer(buffer, nestedPrefix))
    }

    override fun serializeNull() {}
}

private class FormUrlMapSerializer(
    private val parent: FormUrlSerializer,
    private val descriptor: SdkFieldDescriptor
) : MapSerializer, PrimitiveSerializer by parent {
    private val buffer = parent.buffer
    private var idx = 0
    private val mapName = descriptor.findTrait<FormUrlMapName>() ?: FormUrlMapName.Default

    private val commonPrefix: String
        get() = when {
            descriptor.hasTrait<FormUrlFlattened>() -> "${descriptor.serialName}.$idx"
            else -> "${descriptor.serialName}.entry.$idx"
        }

    private fun writeKey(key: String) {
        idx++
        if (buffer.writePosition > 0u) buffer.write("&")

        val encodedKey = key.urlEncodeComponent()
        buffer.write("$commonPrefix.${mapName.key}=$encodedKey")
    }

    private fun writeEntry(key: String, block: () -> Unit) {
        writeKey(key)
        buffer.write("&")
        buffer.write("$commonPrefix.${mapName.value}=")
        block()
    }

    override fun entry(key: String, value: Boolean?) = writeEntry(key) {
        checkNotSparse(value)
        serializeBoolean(value)
    }

    override fun entry(key: String, value: Byte?) = writeEntry(key) {
        checkNotSparse(value)
        serializeByte(value)
    }

    override fun entry(key: String, value: Short?) = writeEntry(key) {
        checkNotSparse(value)
        serializeShort(value)
    }

    override fun entry(key: String, value: Char?) = writeEntry(key) {
        checkNotSparse(value)
        serializeChar(value)
    }

    override fun entry(key: String, value: Int?) = writeEntry(key) {
        checkNotSparse(value)
        serializeInt(value)
    }

    override fun entry(key: String, value: Long?) = writeEntry(key) {
        checkNotSparse(value)
        serializeLong(value)
    }

    override fun entry(key: String, value: Float?) = writeEntry(key) {
        checkNotSparse(value)
        serializeFloat(value)
    }

    override fun entry(key: String, value: Double?) = writeEntry(key) {
        checkNotSparse(value)
        serializeDouble(value)
    }

    override fun entry(key: String, value: String?) = writeEntry(key) {
        checkNotSparse(value)
        serializeString(value)
    }

    override fun entry(key: String, value: Instant?, format: TimestampFormat) = writeEntry(key) {
        checkNotSparse(value)
        serializeInstant(value, format)
    }

    override fun entry(key: String, value: SdkSerializable?) {
        checkNotSparse(value)
        writeKey(key)

        val nestedPrefix = "$commonPrefix.${mapName.value}."
        value.serialize(FormUrlSerializer(buffer, nestedPrefix))
    }

    override fun listEntry(key: String, listDescriptor: SdkFieldDescriptor, block: ListSerializer.() -> Unit) {
        writeKey(key)

        val childDescriptor = SdkFieldDescriptor(SerialKind.List, FormUrlSerialName("$commonPrefix.${mapName.value}"))
        FormUrlListSerializer(parent, childDescriptor).apply(block)
    }

    override fun mapEntry(key: String, mapDescriptor: SdkFieldDescriptor, block: MapSerializer.() -> Unit) {
        writeKey(key)

        val childDescriptor = SdkFieldDescriptor(SerialKind.Map, FormUrlSerialName("$commonPrefix.${mapName.value}"))
        FormUrlMapSerializer(parent, childDescriptor).apply(block)
    }

    override fun endMap() {}
}

private fun SdkByteBuffer.commonWriteNumber(value: Number): Unit = write(value.toString())

// like checkNotNull() but throws the correct serialization exception
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
private inline fun <T : Any> checkNotSparse(value: T?): T {
    contract {
        returns() implies (value != null)
    }
    if (value == null) throw SerializationException("sparse collections are not supported by form-url encoding")
    return value
}

private fun QueryLiteral.toDescriptor(): SdkFieldDescriptor = SdkFieldDescriptor(SerialKind.String, FormUrlSerialName(key))

private val SdkFieldDescriptor.serialName: String
    get() = expectTrait<FormUrlSerialName>().name

private fun SdkFieldDescriptor.copyWithNewSerialName(newName: String): SdkFieldDescriptor {
    val newTraits = traits.filterNot { it is FormUrlSerialName }.toMutableSet()
    newTraits.add(FormUrlSerialName(newName))
    return SdkFieldDescriptor(kind, newTraits)
}
