/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.http.config

/**
 * The user-accessible configuration properties for configuring request compression.
 */
public interface CompressionClientConfig {
    /**
     * Flag used to determine when a request should be compressed or not.
     * False by default.
     */
    public val disableRequestCompression: Boolean

    /**
     * The threshold in bytes used to determine when a request should be compressed.
     * Looks at payload size.
     * Should be in range: 0-10485760.
     * 10240 by default.
     */
    public val requestMinCompressionSizeBytes: Long

    public interface Builder {
        /**
         * Flag used to determine when a request should be compressed or not.
         * False by default.
         */
        public var disableRequestCompression: Boolean?

        /**
         * The threshold in bytes used to determine when a request should be compressed.
         * Looks at payload size.
         * Should be in range: 0-10485760.
         * 10240 by default.
         */
        public var requestMinCompressionSizeBytes: Long?
    }
}