package aws.smithy.kotlin.runtime.client.config

import aws.smithy.kotlin.runtime.InternalApi

/**
 * The retry mode to be used for the client's retry strategy.
 */
@InternalApi
public enum class RetryMode {
    /**
     * The legacy retry mode is supported for compatibility with other SDKs and existing configurations for them,
     * but it works exactly the same as the standard retry mode for this SDK.
     */
    LEGACY,

    /**
     * The standard retry mode. With this, the client will use the [StandardRetryStrategy][aws.smithy.kotlin.runtime.retries.StandardRetryStrategy]
     */
    STANDARD,

    /**
     * Not implemented yet. https://github.com/awslabs/aws-sdk-kotlin/issues/701
     */
    ADAPTIVE,
}
