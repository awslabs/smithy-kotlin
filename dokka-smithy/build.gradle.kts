/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
plugins {
    kotlin("jvm")
}

description = "Custom Dokka plugin for Kotlin Smithy SDK API docs"

dependencies {
    val dokkaVersion: String by project
    compileOnly("org.jetbrains.dokka:dokka-base:$dokkaVersion")
    compileOnly("org.jetbrains.dokka:dokka-core:$dokkaVersion")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        allWarningsAsErrors.set(false) // FIXME Dokka bundles stdlib into the classpath, causing an unfixable warning
    }
}
