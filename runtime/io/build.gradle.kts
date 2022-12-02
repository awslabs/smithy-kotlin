/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

buildscript {
    val atomicFuVersion: String by project
    repositories {
        mavenCentral()
    }

    dependencies {
        classpath("org.jetbrains.kotlinx:atomicfu-gradle-plugin:$atomicFuVersion")
    }
}

description = "IO primitives for Smithy services generated by smithy-kotlin"
extra["displayName"] = "Smithy :: Kotlin :: IO"
extra["moduleName"] = "aws.smithy.kotlin.runtime.io"

val coroutinesVersion: String by project
val okioVersion: String by project
val atomicFuVersion: String by project

apply(plugin = "kotlinx-atomicfu")

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":runtime:utils"))
                implementation(project(":runtime:hashing"))

                implementation("com.squareup.okio:okio:$okioVersion")
                implementation("org.jetbrains.kotlinx:atomicfu:$atomicFuVersion")

                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
            }
        }

        commonTest {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
                implementation(project(":runtime:testing"))
            }
        }

        all {
            languageSettings.optIn("aws.smithy.kotlin.runtime.util.InternalApi")
        }
    }
}
