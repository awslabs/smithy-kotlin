/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package aws.smithy.kotlin.runtime.serde.json

import aws.smithy.kotlin.runtime.serde.*
import aws.smithy.kotlin.runtime.util.*
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

private val DIGITS = ('0'..'9').toSet()
private val EXP = setOf('e', 'E')
private val PLUS_MINUS = setOf('-', '+')

private typealias StateStack = ListStack<LexerState>
private typealias StateMutation = (StateStack) -> Unit

/**
 * Manages internal lexer state
 *
 * The entire lexer works off peeking tokens. Only when nextToken() is called should state be mutated.
 * State manager helps enforce this invariant.
 */
private data class StateManager(
    private val state: StateStack = mutableListOf(LexerState.Initial),
    private val pendingMutations: MutableList<StateMutation> = mutableListOf()
) {

    /**
     * The size of the state stack
     */
    val size: Int
        get() = state.size

    /**
     * Remove all pending mutations and run them to bring state up to date
     */
    fun update() {
        pendingMutations.forEach { it.invoke(state) }
        pendingMutations.clear()
    }

    /**
     * Push a pending mutation
     */
    fun mutate(mutation: StateMutation) { pendingMutations.add(mutation) }

    /**
     * Get the top of the state stack
     */
    fun current(): LexerState = state.top()
}

/**
 * Tokenizes JSON documents
 */
internal class JsonLexer(
    cs: CharStream
) : JsonStreamReader {
    private var peeked: JsonToken? = null
    private val state = StateManager()
    private val data: ByteArray = cs.bytes()
    private var idx = 0

    override suspend fun nextToken(): JsonToken {
        val next = peek()
        peeked = null
        state.update()
        return next
    }

    override suspend fun peek(): JsonToken = peeked ?: doPeek().also { peeked = it }

    override suspend fun skipNext() {
        val startDepth = state.size
        nextToken()
        while (state.size > startDepth) {
            nextToken()
        }
    }

    private fun doPeek(): JsonToken {
        try {
            return when (state.current()) {
                LexerState.Initial -> readToken()
                LexerState.ArrayFirstValueOrEnd -> stateArrayFirstValueOrEnd()
                LexerState.ArrayNextValueOrEnd -> stateArrayNextValueOrEnd()
                LexerState.ObjectFirstKeyOrEnd -> stateObjectFirstKeyOrEnd()
                LexerState.ObjectNextKeyOrEnd -> stateObjectNextKeyOrEnd()
                LexerState.ObjectFieldValue -> stateObjectFieldValue()
            }
        } catch (ex: DeserializationException) {
            throw ex
        } catch (ex: Exception) {
            throw DeserializationException(cause = ex)
        }
    }

    // handles the [State.ObjectFirstKeyOrEnd] state
    private fun stateObjectFirstKeyOrEnd(): JsonToken =
        when (val chr = nextNonWhitespace(peek = true)) {
            '}' -> endObject()
            '"' -> readName()
            else -> throw unexpectedToken(chr, "\"", "}")
        }

    // handles the [State.ObjectNextKeyOrEnd] state
    private fun stateObjectNextKeyOrEnd(): JsonToken =
        when (val chr = nextNonWhitespace(peek = true)) {
            '}' -> endObject()
            ',' -> {
                consume(',')
                nextNonWhitespace(peek = true)
                readName()
            }
            else -> throw unexpectedToken(chr, ",", "}")
        }

    // handles the [State.ObjectFieldValue] state
    private fun stateObjectFieldValue(): JsonToken =
        when (val chr = nextNonWhitespace(peek = true)) {
            ':' -> {
                consume(':')
                state.mutate { it.replaceTop(LexerState.ObjectNextKeyOrEnd) }
                readToken()
            }
            else -> throw unexpectedToken(chr, ":")
        }

    // handles the [State.ArrayFirstValueOrEnd] state
    private fun stateArrayFirstValueOrEnd(): JsonToken =
        when (nextNonWhitespace(peek = true)) {
            ']' -> endArray()
            else -> {
                state.mutate { it.replaceTop(LexerState.ArrayNextValueOrEnd) }
                readToken()
            }
        }

    // handles the [State.ArrayNextValueOrEnd] state
    private fun stateArrayNextValueOrEnd(): JsonToken =
        when (val chr = nextNonWhitespace(peek = true)) {
            ']' -> endArray()
            ',' -> {
                consume(',')
                readToken()
            }
            else -> throw unexpectedToken(chr, ",", "]")
        }

    // discards the '{' character and pushes 'ObjectFirstKeyOrEnd' state
    private fun startObject(): JsonToken {
        consume('{')
        state.mutate { it.push(LexerState.ObjectFirstKeyOrEnd) }
        return JsonToken.BeginObject
    }

    // discards the '}' character and pops the current state
    private fun endObject(): JsonToken {
        consume('}')
        val top = state.current()
        lexerCheck(top == LexerState.ObjectFirstKeyOrEnd || top == LexerState.ObjectNextKeyOrEnd) { "Unexpected close `}` encountered" }
        state.mutate { it.pop() }
        return JsonToken.EndObject
    }

    // discards the '[' and pushes 'ArrayFirstValueOrEnd' state
    private fun startArray(): JsonToken {
        consume('[')
        state.mutate { it.push(LexerState.ArrayFirstValueOrEnd) }
        return JsonToken.BeginArray
    }

    // discards the '}' character and pops the current state
    private fun endArray(): JsonToken {
        consume(']')
        val top = state.current()
        lexerCheck(top == LexerState.ArrayFirstValueOrEnd || top == LexerState.ArrayNextValueOrEnd) { "Unexpected close `]` encountered" }
        state.mutate { it.pop() }
        return JsonToken.EndArray
    }

    // read an object key
    private fun readName(): JsonToken {
        val name = when (val chr = peekOrThrow()) {
            '"' -> readQuoted()
            else -> throw unexpectedToken(chr, "\"")
        }
        state.mutate { it.replaceTop(LexerState.ObjectFieldValue) }
        return JsonToken.Name(name)
    }

    // read the next token from the stream. This is only invoked from state functions which guarantees
    // the current state should be such that the next character is the start of a token
    private fun readToken(): JsonToken =
        when (val chr = nextNonWhitespace(peek = true)) {
            '{' -> startObject()
            '[' -> startArray()
            '"' -> JsonToken.String(readQuoted())
            't', 'f', 'n' -> readKeyword()
            '-', in '0'..'9' -> readNumber()
            null -> JsonToken.EndDocument
            else -> throw unexpectedToken(chr, "{", "[", "\"", "null", "true", "false", "<number>")
        }

    /**
     * Read based on the number spec : https://www.json.org/json-en.html
     * [-]0-9[.[0-9]][[E|e][+|-]0-9]
     */
    private fun readNumber(): JsonToken {
        val value = buildString {
            if (peekChar() == '-') {
                append(nextOrThrow())
            }
            readDigits(this)
            if (peekChar() == '.') {
                append(nextOrThrow())
                readDigits(this)
            }
            if (peekChar() in EXP) {
                append(nextOrThrow())
                if (peekChar() in PLUS_MINUS) {
                    append(nextOrThrow())
                }
                readDigits(this)
            }
        }
        lexerCheck(value.isNotEmpty()) { "Invalid number, expected '-' || 0..9, found ${peekChar()}" }
        return JsonToken.Number(value)
    }

    private fun readDigits(appendable: Appendable) {
        while (peekChar() in DIGITS) {
            appendable.append(nextOrThrow())
        }
    }

    // reads a quoted JSON string out of the stream
    private fun readQuoted(): String {
        consume('"')
        // read bytes until a non-escaped end-quote
        val start = idx
        var chr = peekOrThrow()
        var needsUnescaped = false
        while (chr != '"') {
            // handle escapes
            when (chr) {
                '\\' -> {
                    needsUnescaped = true
                    // consume escape backslash
                    nextOrThrow()
                    when (val byte = nextOrThrow()) {
                        'u' -> {
                            if (idx + 4 >= data.size) throw DeserializationException("Unexpected EOF")
                            idx += 4
                        }
                        '\\', '/', '"', 'b', 'f', 'r', 'n', 't' -> { } // already consumed
                        else -> throw DeserializationException("Invalid escape character: `$byte`")
                    }
                }
                else -> {
                    if (chr.isControl()) throw DeserializationException("Unexpected control character: `$chr`")
                    idx++
                }
            }

            chr = peekOrThrow()
        }

        val value = data.decodeToString(start, idx)
        consume('"')
        return if (needsUnescaped) value.unescape() else value
    }

    private fun readKeyword(): JsonToken = when (val ch = peekOrThrow()) {
        't' -> readLiteral("true", JsonToken.Bool(true))
        'f' -> readLiteral("false", JsonToken.Bool(false))
        'n' -> readLiteral("null", JsonToken.Null)
        else -> throw DeserializationException("Unable to handle keyword starting with '$ch'")
    }

    private fun readLiteral(expectedString: String, token: JsonToken): JsonToken {
        consume(expectedString)
        return token
    }

    /**
     * Advance the cursor until next non-whitespace character is encountered
     * @param peek Flag indicating if the next non-whitespace character should be consumed or peeked
     */
    private fun nextNonWhitespace(peek: Boolean = false): Char? {
        while (peekChar()?.isWhitespace() == true) {
            idx++
        }
        return if (peek) peekChar() else nextOrThrow()
    }

    /**
     * Invoke [consume] for each character in [expected]
     */
    private fun consume(expected: String) = expected.forEach { consume(it) }

    /**
     * Assert that the next character is [expected] and advance
     */
    private fun consume(expected: Char) {
        val chr = data[idx].toChar()
        check(chr == expected) { "Unexpected char '$chr' expected '$expected'" }
        idx++
    }

    /**
     * Return next byte to consume or null if EOF has been reached
     */
    private fun peekByte(): Byte? = data.getOrNull(idx)

    /**
     * Peek the next character or return null if EOF has been reached
     *
     * NOTE: This assumes ascii/single byte UTF-8. This is safe because we only use it for tokenization
     * where we expect to read ascii chars. When reading object keys or string values [readQuoted] is
     * used which handles UTF-8.
     */
    private fun peekChar(): Char? = peekByte()?.toInt()?.toChar()

    /**
     * Peek the next character or throw if EOF has been reached
     */
    private fun peekOrThrow(): Char = peekChar() ?: throw IllegalStateException("Unexpected EOF")

    /**
     * Consume the next character and advance the index or throw if EOF has been reached
     */
    private fun nextOrThrow(): Char = peekOrThrow().also { idx++ }
}

/**
 * Unescape a JSON string (either object key or string value)
 */
private fun String.unescape(): String {
    val str = this
    return buildString(str.length + 1) {
        var i = 0
        while (i < str.length) {
            val chr = str[i]
            when (chr) {
                '\\' -> {
                    i++ // consume backslash
                    when (val byte = str[i++]) {
                        'u' -> {
                            i += readEscapedUnicode(str, i, this)
                        }
                        '\\' -> append('\\')
                        '/' -> append('/')
                        '"' -> append('"')
                        'b' -> append('\b')
                        'f' -> append("\u000C")
                        'r' -> append('\r')
                        'n' -> append('\n')
                        't' -> append('\t')
                        else -> throw DeserializationException("Invalid escape character: `$byte`")
                    }
                }
                else -> {
                    append(chr)
                    i++
                }
            }
        }
    }
}

/**
 * Reads an escaped unicode code point from [s] starting at [start] offset. This assumes that '\u' has already
 * been consumed and [start] is pointing to the first hex digit. If the code point represents a surrogate pair
 * an additional escaped code point will be consumed from the string.
 * @param s The string to decode from
 * @param start The starting index to start reading from
 * @param sb The string builder to append unescaped unicode characters to
 * @return The number of characters consumed
 */
private fun readEscapedUnicode(s: String, start: Int, sb: StringBuilder): Int {
    // already consumed \u escape, take next 4 bytes as high
    val high = s.substring(start, start + 4).decodeEscapedCodePoint()
    var consumed = 4
    if (high.isHighSurrogate()) {
        val lowStart = start + consumed
        val escapedLow = s.substring(lowStart, lowStart + 6)
        lexerCheck(escapedLow.startsWith("\\u")) { "Expected surrogate pair, found `$escapedLow`" }
        val low = escapedLow.substring(2).decodeEscapedCodePoint()
        lexerCheck(low.isLowSurrogate()) { "Invalid surrogate pair: (${high.code}, ${low.code})" }
        sb.append(high, low)
        consumed += 6
    } else {
        sb.append(high)
    }
    return consumed
}

/**
 * decode an escaped unicode character to an integer code point (e.g. D801)
 * the escape characters `\u` should be stripped from the input before calling
 */
private fun String.decodeEscapedCodePoint(): Char {
    if (!all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw DeserializationException("Invalid unicode escape: `\\u$this`")
    return toInt(16).toChar()
}

@OptIn(ExperimentalContracts::class)
private inline fun lexerCheck(value: Boolean, lazyMessage: () -> Any) {
    contract {
        returns() implies value
    }
    if (!value) {
        val message = lazyMessage()
        throw DeserializationException(message.toString())
    }
}

/**
 * Test whether a character is a control character (ignoring SP and DEL)
 */
private fun Char.isControl(): Boolean = code in 0x00..0x1F

private fun unexpectedToken(found: Char?, vararg expected: String): DeserializationException {
    val pluralModifier = if (expected.size > 1) " one of" else ""
    val formatted = expected.joinToString(separator = ", ") { "'$it'" }
    return DeserializationException("Unexpected token '$found', expected$pluralModifier $formatted")
}
