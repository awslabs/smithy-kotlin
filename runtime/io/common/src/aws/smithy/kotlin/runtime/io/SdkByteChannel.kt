/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.smithy.kotlin.runtime.io

import aws.smithy.kotlin.runtime.util.InternalApi

public const val DEFAULT_BYTE_CHANNEL_BUFFER_SIZE: Long = 8192

/**
 * Channel for asynchronous reading and writing sequences of bytes. Conceptually a pipe
 * with a reader on one end, decoupled from a writer on the other.
 *
 * This is a buffered **single-reader single writer channel**.
 *
 * Read operations can be invoked concurrently with write operations, but multiple reads or multiple writes
 * cannot be invoked concurrently with themselves. Exceptions are [close] and [flush] which can be invoked
 * concurrently with other operations including between themselves at any time.
 */
public interface SdkByteChannel : SdkByteReadChannel, SdkByteWriteChannel {
    override fun close() {
        (this as SdkByteWriteChannel).close()
    }
}

/**
 * Create a buffered channel for asynchronous reading and writing of bytes
 * @param autoFlush Flag indicating if the channel should auto flush after every read, see [SdkByteWriteChannel.autoFlush]
 */
@InternalApi
public fun SdkByteChannel(
    autoFlush: Boolean = true,
    maxBufferSize: Long = DEFAULT_BYTE_CHANNEL_BUFFER_SIZE,
): SdkByteChannel = TODO()

/**
 * Creates a channel for reading from the given byte array.
 */
@InternalApi
public fun SdkByteReadChannel(
    content: ByteArray,
    offset: Int = 0,
    length: Int = content.size - offset,
): SdkByteReadChannel = TODO()
