/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package software.aws.clientrt.http

/**
 * Dsl marker for [SdkHttpClient] dsl.
 */
@DslMarker
annotation class HttpClientDsl

/**
 * Configuration settings for [SdkHttpClient]
 */
@HttpClientDsl
class HttpClientConfig
