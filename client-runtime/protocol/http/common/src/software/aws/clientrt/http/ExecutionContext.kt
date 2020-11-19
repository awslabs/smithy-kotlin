/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package software.aws.clientrt.http

import software.aws.clientrt.util.AttributeKey
import software.aws.clientrt.util.Attributes

/**
 * Features are registered at the [software.aws.clientrt.http.SdkHttpClient] level and are executed
 * on every request/response. The data flowing through those pipelines changes with every call though.
 *
 * ExecutionContext is the request and response pipeline per/operation context (metadata) that features can use
 * to drive behavior that is specific to a particular request or response.
 */
class ExecutionContext private constructor(builder: ExecutionContextBuilder) {
    // TODO - propagate Job() and/or coroutine context?

    /**
     * Default construct an [ExecutionContext]. Note: this is not usually useful without configuring the call attributes
     */
    constructor() : this(ExecutionContextBuilder())

    /**
     * Attributes associated with this particular execution/call
     */
    val attributes: Attributes = builder.attributes

    // like [attribute] but returns null if not present
    fun <T : Any> attributeOrNull(key: AttributeKey<T>): T? = attributes.getOrNull(key)

    // get an attribute by [key] or throws an exception if key is not present
    fun <T : Any> attribute(key: AttributeKey<T>): T = attributes[key]

    companion object {
        fun build(block: ExecutionContextBuilder.() -> Unit): ExecutionContext = ExecutionContextBuilder().apply(block).build()
    }

    class ExecutionContextBuilder {
        var attributes: Attributes = Attributes()

        fun build(): ExecutionContext = ExecutionContext(this)
    }
}
