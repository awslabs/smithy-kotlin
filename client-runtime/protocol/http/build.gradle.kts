/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

description = "HTTP Core for Smithy services generated by smithy-kotlin"
extra["displayName"] = "Smithy :: Kotlin :: HTTP Core"
extra["moduleName"] = "software.aws.clientrt.http"

kotlin {

    sourceSets {
        commonMain {
            dependencies {
                api(project(":client-runtime:client-rt-core"))
                // exposes Attributes
                api(project(":client-runtime:utils"))
                implementation(project(":client-runtime:io"))
            }
        }

        commonTest {
            dependencies {
                implementation(project(":client-runtime:testing"))
                // for testing a concrete provider
                implementation(project(":client-runtime:serde:serde-json"))
            }
        }
    }
}