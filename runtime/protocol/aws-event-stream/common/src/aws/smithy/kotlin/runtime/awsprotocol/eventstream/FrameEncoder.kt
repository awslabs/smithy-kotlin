/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.smithy.kotlin.runtime.awsprotocol.eventstream

import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.http.HttpBody
import aws.smithy.kotlin.runtime.io.SdkBuffer
import aws.smithy.kotlin.runtime.io.SdkByteChannel
import aws.smithy.kotlin.runtime.io.SdkByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Transform the stream of messages into a stream of raw bytes. Each
 * element of the resulting flow is the encoded version of the corresponding message
 */
@InternalApi
public fun Flow<Message>.encode(): Flow<SdkBuffer> = map {
    val buffer = SdkBuffer()
    it.encode(buffer)
    buffer
}

/**
 * Transform a stream of encoded messages into an [HttpBody].
 * @param scope parent scope to launch a coroutine in that consumes the flow and populates a [SdkByteReadChannel]
 */
@InternalApi
public suspend fun Flow<SdkBuffer>.asEventStreamHttpBody(scope: CoroutineScope): HttpBody {
    val encodedMessages = this
    val ch = SdkByteChannel(true)

    return object : HttpBody.ChannelContent() {
        override val contentLength: Long? = null
        override val isOneShot: Boolean = true
        override val isDuplex: Boolean = true

        private var job: Job? = null

        override fun readFrom(): SdkByteReadChannel {
            // FIXME - delaying launch here until the channel is consumed from the HTTP engine is a hacky way
            //  of enforcing ordering to ensure the ExecutionContext is updated with the
            //  AwsSigningAttributes.RequestSignature by the time the messages are collected and sign() is called

            // Although rare, nothing stops downstream consumers from invoking readFrom() more than once.
            // Only launch background collection task on first call
            if (job == null) {
                job = scope.launch {
                    encodedMessages.collect {
                        ch.write(it)
                    }
                }

                job?.invokeOnCompletion { cause ->
                    ch.close(cause)
                }
            }

            return ch
        }
    }
}
