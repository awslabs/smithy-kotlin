/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package aws.smithy.kotlin.runtime.serde

import aws.smithy.kotlin.runtime.io.SdkByteReadChannel
import aws.smithy.kotlin.runtime.testing.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

abstract class CharStreamTest {
    abstract fun newCharStream(contents: String): CharStream

    @Test
    fun canCreateACharStreamFromAByteArray() = runSuspendTest {
        val sut = newCharStream("hello")
        val contents = sut.readAll()
        assertEquals("hello", contents)
    }

    @Test
    fun peekDoesNotAffectStreamPosition() = runSuspendTest {
        val sut = newCharStream("hello")
        assertEquals('h', sut.peek())
        assertEquals('h', sut.peek())
        assertEquals('h', sut.next())
        assertEquals('e', sut.peek())
    }

    @Test
    fun consumeTheExpectedCharacterThrowingIfNoMatch() = runSuspendTest {
        val sut = newCharStream("hello")

        sut.consume('h')

        assertFailsWith<IllegalStateException> { sut.consume('h') }
        assertEquals("ello", sut.readAll())
    }

    @Test
    fun consumeTheExpectedCharSequenceThrowingIfNoMatch() = runSuspendTest {
        val sut = newCharStream("hello")

        sut.consume("he")

        assertFailsWith<IllegalStateException> { sut.consume("he") }
        assertEquals("llo", sut.readAll())
    }

    @Test
    fun canReadUntilAPredicateIsHit() = runSuspendTest {
        val sut = newCharStream("hello world")

        val read = sut.readUntil { it.isWhitespace() }

        assertEquals("hello", read)
        assertEquals(" world", sut.readAll())
    }

    private suspend fun CharStream.readAll(): String = buildString {
        while (peek() != null) {
            append(next())
        }
    }
}

class ByteArrayBackedCharStreamTest : CharStreamTest() {
    override fun newCharStream(contents: String): CharStream = CharStream(contents.encodeToByteArray())
}

class ReadChannelCharStreamTest : CharStreamTest() {
    override fun newCharStream(contents: String): CharStream = CharStream(SdkByteReadChannel(contents.encodeToByteArray()))
}
