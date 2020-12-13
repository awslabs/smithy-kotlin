/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SymbolBuilderTest {

    @Test
    fun `it builds symbols`() {
        val x = buildSymbol {
            name = "Foo"
            dependencies += KotlinDependency.CLIENT_RT_CORE
            reference {
                name = "MyRef"
            }
            namespace = "com.mypkg"
            definitionFile = "Foo.kt"
            declarationFile = "Foo.kt"
            defaultValue = "fooey"
            properties {
                set("key", "value")
                set("key2", "value2")
                remove("key2")
            }
        }

        assertEquals("Foo", x.name)
        assertEquals("com.mypkg", x.namespace)
        assertEquals("Foo.kt", x.declarationFile)
        assertEquals("Foo.kt", x.definitionFile)
        assertEquals("value", x.getProperty("key").get())
        assertTrue(x.getProperty("key2").isEmpty)
        assertEquals(1, x.references.size)
        assertEquals(1, x.dependencies.size)
    }
}
