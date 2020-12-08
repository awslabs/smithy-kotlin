/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package software.aws.clientrt.http.response

import software.aws.clientrt.http.ExecutionContext
import software.aws.clientrt.http.util.Phase
import software.aws.clientrt.http.util.Pipeline
import kotlin.reflect.KClass

/**
 * Container for desired output type info
 */
data class TypeInfo(val classz: KClass<*>)

/**
 * A container for the HttpResponsePipeline context parameter
 *
 * @property response The raw HTTP response container
 * @property want Type information about the desired output type
 * @property executionContext (Optional) user data passed to the response pipeline that features can choose to take
 * advantage of.
 */
data class HttpResponseContext(val response: HttpResponse, val want: TypeInfo, val executionContext: ExecutionContext)

/**
 * Response pipeline that can be hooked into to transform an [HttpResponse] into an instance
 * of an expected type.
 *
 * The subject always starts as the response. It is the expectation that the pipeline is configured
 * in a way to make the desired transformation happen.
 */
class HttpResponsePipeline : Pipeline<Any, HttpResponseContext>(Receive, Transform, Finalize) {

    companion object {
        /**
         * Execute any tasks before starting transformations on the response (e.g. inspect HTTP response headers)
         */
        val Receive = Phase("Receive")

        /**
         * Transform the response body to the expected format
         */
        val Transform = Phase("Transform")

        /**
         * Perform any final modifications to the response
         */
        val Finalize = Phase("Finalize")
    }
}
