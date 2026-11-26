package com.baystudio.droide.core

import java.util.concurrent.TimeUnit
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8

/** Per HTTP response. Event-stream line endings may be LF, CRLF, or a lone CR. */
internal class ProviderLineReader(
    private val source: BufferedSource,
    private val maxBytes: Int = LlmClient.MAX_RESPONSE_CHARS,
) {
    init { require(maxBytes > 0) }
    private var firstLine = true
    private var skipLf = false

    fun readLine(idle: ProviderStreamIdleGuard): String? {
        idle.check()
        val timeout = source.timeout()
        val oldTimeout = timeout.timeoutNanos()
        val oldDeadline = if (timeout.hasDeadline()) timeout.deadlineNanoTime() else null
        val now = System.nanoTime()
        val idleDeadline = idle.deadlineNanos()
        val deadline = if (oldDeadline != null && oldDeadline - now < idleDeadline - now) oldDeadline else idleDeadline
        try {
            timeout.timeout(idle.timeoutMillis, TimeUnit.MILLISECONDS)
            timeout.deadlineNanoTime(deadline)
            return readLine()
        } finally {
            if (oldDeadline == null) timeout.clearDeadline() else timeout.deadlineNanoTime(oldDeadline)
            timeout.timeout(oldTimeout, TimeUnit.NANOSECONDS)
        }
    }

    fun readLine(): String? {
        if (firstLine) {
            firstLine = false
            if (source.request(1) && source.buffer[0L] == BOM[0] && source.rangeEquals(0L, BOM)) {
                source.skip(BOM.size.toLong())
            }
        }
        // Read a possible LF on the next call. A blank CR must dispatch the event
        // immediately even if the server keeps its connection open and idle.
        if (skipLf) {
            skipLf = false
            if (source.request(1) && source.buffer[0L] == LF) source.skip(1)
        }

        var scanned = 0L
        while (source.request(scanned + 1)) {
            val buffer = source.buffer
            val delimiter = buffer.indexOfElement(DELIMITERS, scanned)
            if (delimiter in 0L..maxBytes.toLong()) {
                val line = source.readUtf8(delimiter)
                skipLf = source.readByte() == CR
                return line
            }
            check(buffer.size <= maxBytes) { "Provider response line exceeds the mobile response capacity" }
            scanned = buffer.size
        }
        return if (scanned == 0L) null else source.readUtf8(scanned)
    }

    private companion object {
        val BOM = "\uFEFF".encodeUtf8()
        val DELIMITERS = "\r\n".encodeUtf8()
        val LF = '\n'.code.toByte()
        val CR = '\r'.code.toByte()
    }
}
