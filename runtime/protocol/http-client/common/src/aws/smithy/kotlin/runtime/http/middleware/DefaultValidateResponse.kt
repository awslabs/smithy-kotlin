/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.http.middleware

import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.SdkBaseException
import aws.smithy.kotlin.runtime.http.*
import aws.smithy.kotlin.runtime.http.operation.ReceiveMiddleware
import aws.smithy.kotlin.runtime.http.operation.SdkHttpRequest
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.http.response.HttpCall
import aws.smithy.kotlin.runtime.io.Handler

/**
 * Generic HTTP service exception
 */
public class HttpResponseException : SdkBaseException {

    public constructor() : super()

    public constructor(message: String?) : super(message)

    public constructor(message: String?, cause: Throwable?) : super(message, cause)

    public constructor(cause: Throwable?) : super(cause)

    /**
     * The HTTP response status code
     */
    public var statusCode: HttpStatusCode? = null

    /**
     * The response headers
     */
    public var headers: Headers? = null

    /**
     * The response payload, if available
     */
    public var body: ByteArray? = null

    /**
     * The original request
     */
    public var request: HttpRequest? = null
}

/**
 * Feature that inspects the HTTP response and throws an exception if it is not successful
 * This is provided for clients generated by smithy-kotlin-codegen. Not expected to be used by AWS
 * services which define specific mappings from an error to the appropriate modeled exception. Out of the
 * box nothing in Smithy gives us that ability (other than the HTTP status code which is not guaranteed unique per error)
 * so all we can do is throw a generic exception with the code and let the user figure out what modeled error it was
 * using whatever matching mechanism they want.
 */
@InternalApi
public class DefaultValidateResponse : ReceiveMiddleware {

    override suspend fun <H : Handler<SdkHttpRequest, HttpCall>> handle(request: SdkHttpRequest, next: H): HttpCall {
        val call = next.call(request)
        if (call.response.status.isSuccess()) {
            return call
        }

        val message = "received unsuccessful HTTP call.response: ${call.response.status}"
        val httpException = HttpResponseException(message).apply {
            statusCode = call.response.status
            headers = call.response.headers
            body = call.response.body.readAll()
            this.request = call.request
        }

        throw httpException
    }
}
