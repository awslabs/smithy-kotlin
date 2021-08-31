/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

// Code generated by smithy-kotlin-codegen. DO NOT EDIT!

package aws.smithy.kotlin.serde.benchmarks.model.twitter

import aws.smithy.kotlin.runtime.serde.Deserializer
import aws.smithy.kotlin.runtime.serde.SdkFieldDescriptor
import aws.smithy.kotlin.runtime.serde.SdkObjectDescriptor
import aws.smithy.kotlin.runtime.serde.SerialKind
import aws.smithy.kotlin.runtime.serde.deserializeList
import aws.smithy.kotlin.runtime.serde.deserializeStruct
import aws.smithy.kotlin.runtime.serde.json.JsonSerialName

internal suspend fun deserializeMediaDocument(deserializer: Deserializer): Media {
    val builder = Media.builder()
    val DISPLAYURL_DESCRIPTOR = SdkFieldDescriptor(SerialKind.String, JsonSerialName("display_url"))
    val EXPANDEDURL_DESCRIPTOR = SdkFieldDescriptor(SerialKind.String, JsonSerialName("expanded_url"))
    val ID_DESCRIPTOR = SdkFieldDescriptor(SerialKind.Long, JsonSerialName("id"))
    val IDSTR_DESCRIPTOR = SdkFieldDescriptor(SerialKind.String, JsonSerialName("id_str"))
    val INDICES_DESCRIPTOR = SdkFieldDescriptor(SerialKind.List, JsonSerialName("indices"))
    val MEDIAURL_DESCRIPTOR = SdkFieldDescriptor(SerialKind.String, JsonSerialName("media_url"))
    val MEDIAURLHTTPS_DESCRIPTOR = SdkFieldDescriptor(SerialKind.String, JsonSerialName("media_url_https"))
    val SIZES_DESCRIPTOR = SdkFieldDescriptor(SerialKind.Struct, JsonSerialName("sizes"))
    val TYPE_DESCRIPTOR = SdkFieldDescriptor(SerialKind.String, JsonSerialName("type"))
    val URL_DESCRIPTOR = SdkFieldDescriptor(SerialKind.String, JsonSerialName("url"))
    val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {
        field(DISPLAYURL_DESCRIPTOR)
        field(EXPANDEDURL_DESCRIPTOR)
        field(ID_DESCRIPTOR)
        field(IDSTR_DESCRIPTOR)
        field(INDICES_DESCRIPTOR)
        field(MEDIAURL_DESCRIPTOR)
        field(MEDIAURLHTTPS_DESCRIPTOR)
        field(SIZES_DESCRIPTOR)
        field(TYPE_DESCRIPTOR)
        field(URL_DESCRIPTOR)
    }

    deserializer.deserializeStruct(OBJ_DESCRIPTOR) {
        loop@while (true) {
            when (findNextFieldIndex()) {
                DISPLAYURL_DESCRIPTOR.index -> builder.displayUrl = deserializeString()
                EXPANDEDURL_DESCRIPTOR.index -> builder.expandedUrl = deserializeString()
                ID_DESCRIPTOR.index -> builder.id = deserializeLong()
                IDSTR_DESCRIPTOR.index -> builder.idStr = deserializeString()
                INDICES_DESCRIPTOR.index ->
                    builder.indices =
                        deserializer.deserializeList(INDICES_DESCRIPTOR) {
                            val col0 = mutableListOf<Int>()
                            while (hasNextElement()) {
                                val el0 = if (nextHasValue()) { deserializeInt() } else { deserializeNull(); continue }
                                col0.add(el0)
                            }
                            col0
                        }
                MEDIAURL_DESCRIPTOR.index -> builder.mediaUrl = deserializeString()
                MEDIAURLHTTPS_DESCRIPTOR.index -> builder.mediaUrlHttps = deserializeString()
                SIZES_DESCRIPTOR.index -> builder.sizes = deserializeSizesDocument(deserializer)
                TYPE_DESCRIPTOR.index -> builder.type = deserializeString()
                URL_DESCRIPTOR.index -> builder.url = deserializeString()
                null -> break@loop
                else -> skipValue()
            }
        }
    }
    return builder.build()
}
