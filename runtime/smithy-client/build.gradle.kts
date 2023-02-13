/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
description = "Client runtime for Smithy services generated by smithy-kotlin"
extra["displayName"] = "Smithy :: Kotlin :: Client Runtime"
extra["moduleName"] = "aws.smithy.kotlin.runtime.client"

val coroutinesVersion: String by project

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":runtime:runtime-core"))
            }
        }
        commonTest {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
                implementation(project(":runtime:testing"))
            }
        }

        all {
            languageSettings.optIn("aws.smithy.kotlin.runtime.InternalApi")
        }
    }
}