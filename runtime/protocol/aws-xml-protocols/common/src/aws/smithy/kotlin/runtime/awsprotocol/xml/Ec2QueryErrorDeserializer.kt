/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.awsprotocol.xml

import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.awsprotocol.ErrorDetails
import aws.smithy.kotlin.runtime.serde.getOrDeserializeErr
import aws.smithy.kotlin.runtime.serde.xml.*

internal data class Ec2QueryErrorResponse(val errors: List<Ec2QueryError>, val requestId: String?)

internal data class Ec2QueryError(val code: String?, val message: String?)

@Deprecated("use parseEc2QueryErrorResponseNoSuspend")
@InternalApi
public suspend fun parseEc2QueryErrorResponse(payload: ByteArray): ErrorDetails =
    parseEc2QueryErrorResponseNoSuspend(payload)

public fun parseEc2QueryErrorResponseNoSuspend(payload: ByteArray): ErrorDetails {
    val response = Ec2QueryErrorResponseDeserializer.deserialize(xmlTagReader(payload))
    val firstError = response.errors.firstOrNull()
    return ErrorDetails(firstError?.code, firstError?.message, response.requestId)
}

/**
 * Deserializes EC2 Query protocol errors as specified by
 * https://smithy.io/2.0/aws/protocols/aws-ec2-query-protocol.html#operation-error-serialization
 */
internal object Ec2QueryErrorResponseDeserializer {
    fun deserialize(root: XmlTagReader): Ec2QueryErrorResponse = runCatching {
        var errors: List<Ec2QueryError>? = null
        var requestId: String? = null
        if (root.tagName != "Response") error("expected <Response> found ${root.tag}")

        loop@while (true) {
            val curr = root.nextTag() ?: break@loop
            when (curr.tagName) {
                "Errors" -> errors = Ec2QueryErrorListDeserializer.deserialize(curr)
                "RequestId" -> requestId = curr.data()
            }
            curr.drop()
        }

        Ec2QueryErrorResponse(errors ?: emptyList(), requestId)
    }.getOrDeserializeErr { "Unable to deserialize EC2Query error" }
}

internal object Ec2QueryErrorListDeserializer {
    fun deserialize(root: XmlTagReader): List<Ec2QueryError> {
        val errors = mutableListOf<Ec2QueryError>()
        loop@while (true) {
            val curr = root.nextTag() ?: break@loop
            when (curr.tagName) {
                "Error" -> {
                    val el = Ec2QueryErrorDeserializer.deserialize(curr)
                    errors.add(el)
                }
            }
            curr.drop()
        }
        return errors
    }
}

internal object Ec2QueryErrorDeserializer {

    fun deserialize(root: XmlTagReader): Ec2QueryError {
        var code: String? = null
        var message: String? = null

        loop@while (true) {
            val curr = root.nextTag() ?: break@loop
            when (curr.tagName) {
                "Code" -> code = curr.data()
                "Message", "message" -> message = curr.data()
            }
            curr.drop()
        }
        return Ec2QueryError(code, message)
    }
}
