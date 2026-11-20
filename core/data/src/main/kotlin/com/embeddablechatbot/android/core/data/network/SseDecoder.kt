package com.embeddablechatbot.android.core.data.network

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

data class SseEvent(val event: String, val data: String)

class SseProtocolException(message: String) : Exception(message)

/**
 * Incremental text/event-stream decoder. Bytes may arrive in arbitrary chunks: UTF-8 sequences and
 * CR/LF/CRLF line endings split across chunks are handled. Comments and unknown fields are ignored.
 */
class SseDecoder(private val maxEventChars: Int = 1_048_576) {
    private val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pendingBytes = ByteArray(0)
    private val line = StringBuilder()
    private var lastWasCr = false
    private var eventName: String? = null
    private val data = StringBuilder()
    private var hasData = false

    fun feed(bytes: ByteArray, length: Int = bytes.size): List<SseEvent> {
        val input = ByteBuffer.wrap(pendingBytes + bytes.copyOf(length))
        val out = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, out, false)
        pendingBytes = ByteArray(input.remaining()).also { input.get(it) }
        out.flip()
        return consume(out)
    }

    /** Call at end of stream. A trailing event without a blank line is discarded, per the SSE spec. */
    fun finish(): List<SseEvent> {
        val out = CharBuffer.allocate(pendingBytes.size + 2)
        decoder.decode(ByteBuffer.wrap(pendingBytes), out, true)
        decoder.flush(out)
        pendingBytes = ByteArray(0)
        out.flip()
        return consume(out)
    }

    private fun consume(chars: CharBuffer): List<SseEvent> {
        val events = mutableListOf<SseEvent>()
        while (chars.hasRemaining()) {
            val c = chars.get()
            when {
                c == '\n' && lastWasCr -> lastWasCr = false
                c == '\r' || c == '\n' -> {
                    lastWasCr = c == '\r'
                    processLine(line.toString())?.let(events::add)
                    line.setLength(0)
                }
                else -> {
                    lastWasCr = false
                    line.append(c)
                    if (line.length + data.length > maxEventChars) throw SseProtocolException("The server sent an oversized event")
                }
            }
        }
        return events
    }

    private fun processLine(text: String): SseEvent? {
        if (text.isEmpty()) {
            val event = if (hasData) SseEvent(eventName ?: "message", data.toString()) else null
            eventName = null
            data.setLength(0)
            hasData = false
            return event
        }
        if (text.startsWith(":")) return null
        val colon = text.indexOf(':')
        val field = if (colon < 0) text else text.substring(0, colon)
        var value = if (colon < 0) "" else text.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> eventName = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }
}
