/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package aws.smithy.kotlin.runtime.io.middleware

import aws.smithy.kotlin.runtime.io.Handler

/**
 * A type that only modifies the output type
 */
interface ModifyResponse<Response> {
    suspend fun modifyResponse(resp: Response): Response
}

/**
 * Adapter for [ModifyResponse] to implement middleware
 */
internal class ModifyResponseMiddleware<Request, Response>(
    private val fn: ModifyResponse<Response>
) : Middleware<Request, Response> {
    override suspend fun <H : Handler<Request, Response>> handle(request: Request, next: H): Response {
        val resp = next.call(request)
        return fn.modifyResponse(resp)
    }
}
