/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.content

import aws.smithy.kotlin.runtime.io.SdkByteReadChannel
import aws.smithy.kotlin.runtime.io.SdkSource
import aws.smithy.kotlin.runtime.io.source
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

fun interface ByteStreamFactory {
    fun inputStream(input: ByteArray): InputStream
    companion object {
        val BYTE_ARRAY: ByteStreamFactory = ByteStreamFactory { input -> ByteStream.fromBytes(input).toInputStream() }

        val SDK_SOURCE: ByteStreamFactory = ByteStreamFactory { input ->
            object : ByteStream.SourceStream() {
                override fun readFrom(): SdkSource = input.source()
                override val contentLength: Long = input.size.toLong()
            }.toInputStream()
        }

        val SDK_CHANNEL: ByteStreamFactory = ByteStreamFactory { input ->
            object : ByteStream.ChannelStream() {
                override fun readFrom(): SdkByteReadChannel = SdkByteReadChannel(input)
                override val contentLength: Long = input.size.toLong()
            }.toInputStream()
        }
    }
}

class ByteStreamBufferInputStreamTest : ByteStreamInputStreamTest(ByteStreamFactory.BYTE_ARRAY)
class ByteStreamSourceStreamInputStreamTest : ByteStreamInputStreamTest(ByteStreamFactory.SDK_SOURCE)
class ByteStreamChannelSourceInputStreamTest : ByteStreamInputStreamTest(ByteStreamFactory.SDK_CHANNEL)

abstract class ByteStreamInputStreamTest(
    private val factory: ByteStreamFactory,
) {
    @Test
    fun testReadOneByteAtATime() {
        val expected = "a lep is a ball".repeat(1024).encodeToByteArray()
        val istream = factory.inputStream(expected)
        val bytes = mutableListOf<Byte>()
        do {
            val next = istream.read()
            if (next >= 0) {
                bytes.add(next.toByte())
            }
        } while (next >= 0)

        val actual = bytes.toByteArray()
        assertEquals(0, istream.available())
        assertEquals(-1, istream.read())
        assertContentEquals(expected, actual)
    }

    @Test
    fun testReadFully() {
        val expected = "a tay is a hammer".repeat(768).encodeToByteArray()
        val istream = factory.inputStream(expected)
        val actual = istream.readBytes()
        assertEquals(0, istream.available())
        assertEquals(-1, istream.read())
        assertContentEquals(expected, actual)
    }

    @Test
    fun testReadOffset() {
        val expected = "a flix is a comb".repeat(1024).encodeToByteArray()
        val istream = factory.inputStream(expected)
        var offset = 0
        val actual = ByteArray(expected.size)
        while (offset < actual.size) {
            val len = minOf(16, actual.size - offset)
            val rc = istream.read(actual, offset, len)
            if (rc == -1) break
            offset += rc
        }

        assertEquals(0, istream.available())
        assertEquals(-1, istream.read())
        assertContentEquals(expected, actual)
    }
}
