/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

description = "x-www-form-urlencoding serialization for Smithy services generated by smithy-kotlin"
extra["displayName"] = "Smithy :: Kotlin :: Serde :: x-www-form-url"
extra["moduleName"] = "aws.smithy.kotlin.runtime.serde.formurl"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":runtime:serde"))
                implementation(project(":runtime:io"))
            }
        }
    }
}
