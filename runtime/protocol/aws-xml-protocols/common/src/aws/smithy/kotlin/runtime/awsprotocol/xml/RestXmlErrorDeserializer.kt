/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.awsprotocol.xml

import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.awsprotocol.ErrorDetails
import aws.smithy.kotlin.runtime.serde.*
import aws.smithy.kotlin.runtime.serde.xml.XmlTagReader
import aws.smithy.kotlin.runtime.serde.xml.data
import aws.smithy.kotlin.runtime.serde.xml.xmlTagReader

/**
 * Provides access to specific values regardless of message form
 */
internal interface RestXmlErrorDetails {
    val requestId: String?
    val code: String?
    val message: String?
}

internal data class XmlError(
    override val requestId: String?,
    override val code: String?,
    override val message: String?,
) : RestXmlErrorDetails

/**
 * Deserializes rest XML protocol errors as specified by:
 * https://awslabs.github.io/smithy/1.0/spec/aws/aws-restxml-protocol.html#error-response-serialization
 *
 * Returns parsed data in normalized form or throws [DeserializationException] if response cannot be parsed.
 */
@Deprecated("use parseRestXmlErrorResponseNoSuspend")
@InternalApi
public suspend fun parseRestXmlErrorResponse(payload: ByteArray): ErrorDetails =
    parseRestXmlErrorResponseNoSuspend(payload)

public fun parseRestXmlErrorResponseNoSuspend(payload: ByteArray): ErrorDetails {
    val details = XmlErrorDeserializer.deserialize(xmlTagReader(payload))
    return ErrorDetails(details.code, details.message, details.requestId)
}

/**
 * This deserializer is used for both wrapped and unwrapped restXml errors.
 */
internal object XmlErrorDeserializer {
    fun deserialize(root: XmlTagReader): XmlError = runCatching {
        var message: String? = null
        var code: String? = null
        var requestId: String? = null

        val rootTagName = root.tagName
        check(rootTagName == "ErrorResponse" || rootTagName == "Error") {
            "expected restXml error response with root tag of <ErrorResponse> or <Error>"
        }

        // wrapped error, unwrap it
        var errTag = root
        if (root.tagName == "ErrorResponse") {
            errTag = root.nextTag() ?: error("expected more tags after <ErrorResponse>")
        }

        if (errTag.tagName == "Error") {
            loop@while (true) {
                val curr = errTag.nextTag() ?: break@loop
                when (curr.tagName) {
                    "Code" -> code = curr.data()
                    "Message", "message" -> message = curr.data()
                    "RequestId" -> requestId = curr.data()
                }
                curr.drop()
            }
        }

        // wrapped responses
        if (requestId == null) {
            loop@while (true) {
                val curr = root.nextTag() ?: break@loop
                when (curr.tagName) {
                    "RequestId" -> requestId = curr.data()
                }
            }
        }

        XmlError(requestId, code, message)
    }.getOrDeserializeErr { "Unable to deserialize RestXml error" }
}
