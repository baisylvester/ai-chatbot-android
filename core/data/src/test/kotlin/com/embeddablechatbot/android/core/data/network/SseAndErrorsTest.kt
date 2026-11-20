package com.embeddablechatbot.android.core.data.network

import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.ErrorReason
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseDecoderTest {
    private val stream = (
        ": keep-alive comment\r\n" +
            "event: accepted\r\ndata: {\"type\":\"accepted\",\"requestId\":\"r1\"}\r\n\r\n" +
            "event: token\ndata: {\"type\":\"token\",\"text\":\"Héllo 👋 – ok\"}\n\n" +
            "event: tool\rdata: {\"type\":\"tool\",\"name\":\"email_manager\",\"phase\":\"start\"}\r\r" +
            "event: done\ndata: {\"type\":\"done\",\ndata: \"turn\":null}\n\n"
        ).toByteArray(Charsets.UTF_8)

    private fun decodeIn(chunks: List<ByteArray>): List<SseEvent> {
        val decoder = SseDecoder()
        return chunks.flatMap { decoder.feed(it) } + decoder.finish()
    }

    @Test
    fun `events are identical however the bytes are split`() {
        val whole = decodeIn(listOf(stream))
        assertEquals(listOf("accepted", "token", "tool", "done"), whole.map { it.event })
        assertEquals("{\"type\":\"token\",\"text\":\"Héllo 👋 – ok\"}", whole[1].data)
        assertEquals("multi-line data is joined with newlines", "{\"type\":\"done\",\n\"turn\":null}", whole[3].data)
        // Every split point, including inside CRLF pairs and multi-byte UTF-8 sequences.
        for (i in 1 until stream.size) {
            assertEquals("split at $i", whole, decodeIn(listOf(stream.copyOfRange(0, i), stream.copyOfRange(i, stream.size))))
        }
        assertEquals("one byte at a time", whole, decodeIn(stream.map { byteArrayOf(it) }))
    }

    @Test
    fun `an unterminated trailing event is discarded at end of stream`() {
        val events = decodeIn(listOf("event: token\ndata: {\"text\":\"a\"}\n\nevent: done\ndata: {\"turn\":1}".toByteArray()))
        assertEquals(listOf("token"), events.map { it.event })
    }

    @Test(expected = SseProtocolException::class)
    fun `oversized events are rejected`() {
        SseDecoder(maxEventChars = 16).feed("data: ${"x".repeat(64)}".toByteArray())
    }
}

class HttpErrorsTest {
    private fun body(code: String, message: String, reason: String? = null) =
        """{"error":{"code":"$code","message":"$message"${reason?.let { ",\"reason\":\"$it\"" } ?: ""}}}"""

    @Test
    fun `conflicts are distinguished by reason, or by documented message on older servers`() {
        assertEquals(AppError.Conflict(ErrorReason.ConversationBusy, "x"), HttpErrors.fromResponse(409, body("conflict", "x", "conversation_busy"), null))
        assertEquals(ErrorReason.MailboxNotConnected, (HttpErrors.fromResponse(409, body("conflict", "Connect Gmail on the Assistant page before using mailbox tools"), null) as AppError.Conflict).reason)
        assertEquals(ErrorReason.RequestMismatch, (HttpErrors.fromResponse(409, body("conflict", "This request ID was already used for another message"), null) as AppError.Conflict).reason)
        assertEquals(ErrorReason.RequestRunning, (HttpErrors.fromResponse(409, body("conflict", "This reply is still running. Refresh the conversation before retrying."), null) as AppError.Conflict).reason)
        assertEquals(ErrorReason.ConversationBusy, (HttpErrors.fromResponse(409, body("conflict", "Another reply is running in this conversation. Wait for it to finish."), null) as AppError.Conflict).reason)
        assertEquals(ErrorReason.StaleApproval, (HttpErrors.fromResponse(409, body("conflict", "changed", "stale_approval"), null) as AppError.Conflict).reason)
    }

    @Test
    fun `status codes map to distinct errors`() {
        assertEquals(AppError.Unauthorized, HttpErrors.fromResponse(401, body("unauthorized", "expired"), null))
        assertEquals(AppError.Forbidden(ErrorReason.MailRoleRequired, "no"), HttpErrors.fromResponse(403, body("forbidden", "no", "mail_role_required"), null))
        assertEquals(AppError.NotFound("gone"), HttpErrors.fromResponse(404, body("not_found", "gone"), null))
        assertTrue(HttpErrors.fromResponse(404, "<!DOCTYPE html><html>Not found</html>", null) is AppError.NotConfigured)
        assertTrue(HttpErrors.fromResponse(410, body("gone", "expired", "transaction_expired"), null) is AppError.Gone)
        assertTrue(HttpErrors.fromResponse(502, body("interrupted", "x"), null) is AppError.Server)
        assertTrue(HttpErrors.fromResponse(500, "<html>", null) is AppError.Server)
        assertEquals(AppError.Invalid("bad"), HttpErrors.fromResponse(400, body("invalid", "bad"), null))
    }

    @Test
    fun `Retry-After accepts seconds and HTTP dates`() {
        assertEquals(AppError.RateLimited(17), HttpErrors.fromResponse(429, null, "17"))
        val now = Instant.parse("2026-11-18T10:00:00Z")
        assertEquals(30L, HttpErrors.retryAfterSeconds("Wed, 18 Nov 2026 10:00:30 GMT", now))
        assertEquals(null, HttpErrors.retryAfterSeconds("soon", now))
    }

    @Test
    fun `transport failures are uncertain only when the request may have left the device`() {
        assertEquals(AppError.Network(uncertain = false), HttpErrors.fromException(UnknownHostException(), idempotent = false))
        assertEquals(AppError.Network(uncertain = true), HttpErrors.fromException(SocketTimeoutException(), idempotent = false))
        assertEquals(AppError.Network(uncertain = true), HttpErrors.fromException(IOException("reset"), idempotent = false))
        assertEquals(AppError.Network(uncertain = false), HttpErrors.fromException(SocketTimeoutException(), idempotent = true))
        assertTrue(HttpErrors.fromException(ContractException("bad"), idempotent = false) is AppError.Protocol)
    }
}
