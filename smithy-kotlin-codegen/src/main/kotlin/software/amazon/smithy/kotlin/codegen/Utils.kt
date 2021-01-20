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

import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId

/**
 * Test if a string is a valid Kotlin identifier name
 */
fun isValidKotlinIdentifier(s: String): Boolean {
    val c = s.firstOrNull() ?: return false
    return when (c) {
        in 'a'..'z', in 'A'..'Z', '_' -> true
        else -> false
    }
}

/**
 * Concise extension function to return a shape of expected type.
 */
inline fun <reified T : Shape> Model.expectShape(shapeId: String): T =
    this.expectShape(ShapeId.from(shapeId), T::class.java)

/**
 * If is member shape returns target, otherwise returns self.
 * @param model for loading the target shape
 */
internal fun Shape.targetOrSelf(model: Model) = when (this) {
    is MemberShape -> model.expectShape(this.target)
    else -> this
}

/**
 * Specifies the type of value the identifier represents
 */
internal enum class NestedIdentifierType(val prefix: String) {
    KEY("k"),                   // Generated variable names for map keys
    VALUE("v"),                 // Generated variable names for map values
    ELEMENT("el"),              // Generated variable name for list elements
    COLLECTION("col"),          // Generated variable name for collection types (list, set)
    MAP("map");                 // Generated variable name for map type
}
/**
 * Generate an identifier for a given nesting level
 * @param type intended type of value
 */
internal fun Int.variableNameFor(type: NestedIdentifierType): String = "${type.prefix}$this"

/**
 * Generate an identifier for a given nesting level
 */
internal fun Int.nestedDescriptorName(): String = "_c$this"