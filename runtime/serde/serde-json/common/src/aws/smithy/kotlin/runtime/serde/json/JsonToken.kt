/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package aws.smithy.kotlin.runtime.serde.json

/**
 * Raw tokens produced when reading a JSON document as a stream
 */
sealed class JsonToken {
    /**
     * The opening of a JSON array '['
     */
    object BeginArray : JsonToken()

    /**
     * The closing of a JSON array ']'
     */
    object EndArray : JsonToken()

    /**
     * The opening of a JSON object '{'
     */
    object BeginObject : JsonToken()

    /**
     * The closing of a JSON object '}'
     */
    object EndObject : JsonToken()

    /**
     * A JSON property name
     */
    data class Name(val value: kotlin.String) : JsonToken()

    /**
     * A JSON string
     */
    data class String(val value: kotlin.String) : JsonToken()

    /**
     * A JSON number (note the raw string value of the number is returned, you are responsible for converting
     * to a concrete [Number] type)
     */
    data class Number(val value: kotlin.String) : JsonToken()

    /**
     * A JSON boolean
     */
    data class Bool(val value: Boolean) : JsonToken()

    /**
     * A JSON 'null'
     */
    object Null : JsonToken()

    /**
     * The end of the JSON stream to signal that the JSON-encoded value has no more
     * tokens
     */
    object EndDocument : JsonToken()

    override fun toString(): kotlin.String = when (this) {
        BeginArray -> "BeginArray"
        EndArray -> "EndArray"
        BeginObject -> "BeginObject"
        EndObject -> "EndObject"
        is Name -> "Name(${this.value})"
        is String -> "String(${this.value})"
        is Number -> "Number(${this.value})"
        is Bool -> "Bool(${this.value})"
        Null -> "Null"
        EndDocument -> "EndDocument"
    }
}
