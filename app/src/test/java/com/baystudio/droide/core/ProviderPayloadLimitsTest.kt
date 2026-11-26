package com.baystudio.droide.core

import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class ProviderPayloadLimitsTest {
    @Test fun endlessProviderLineIsRejectedBeforeUnboundedBuffering() {
        var readBytes = 0L
        val upstream = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = minOf(byteCount, 8192).toInt()
                sink.write(ByteArray(count) { 'x'.code.toByte() })
                readBytes += count
                return count.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }
        upstream.buffer().use { source ->
            assertThrows(IllegalStateException::class.java) { ProviderLineReader(source, 1024).readLine() }
            assertTrue("Must not drain an endless server response", readBytes <= 1024 + 8192)
        }
    }

    @Test fun boundedLineReaderPreservesCrLfUnicodeBlankLinesAndFinalLine() {
        val lines = ProviderLineReader(Buffer().writeUtf8("data: halo 🌏\r\n\nfinal"), 100)
        assertEquals("data: halo 🌏", lines.readLine())
        assertEquals("", lines.readLine())
        assertEquals("final", lines.readLine())
        assertNull(lines.readLine())
    }

    @Test fun exactCapacityFinalLineIsAllowedAndOneMoreByteIsRejected() {
        assertEquals("1234", ProviderLineReader(Buffer().writeUtf8("1234"), 4).readLine())
        assertEquals("1234", ProviderLineReader(Buffer().writeUtf8("1234\n"), 4).readLine())
        assertThrows(IllegalStateException::class.java) { ProviderLineReader(Buffer().writeUtf8("12345"), 4).readLine() }
    }

    @Test fun wireReaderAcceptsOneInitialBomAndMixedSeparatorsAcrossChunks() {
        val wire = Buffer().writeUtf8("\uFEFF1234\r\n\uFEFFx\r\ry\nz")
        val upstream = object : Source {
            override fun read(sink: Buffer, byteCount: Long) = wire.read(sink, minOf(byteCount, 1L))
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }
        val lines = ProviderLineReader(upstream.buffer(), 4)
        assertEquals(listOf("1234", "\uFEFFx", "", "y", "z"), generateSequence { lines.readLine() }.toList())
    }

    @Test fun loneCrDispatchesBeforeTheNextByteArrives() {
        val upstream = object : Source {
            var supplied = false
            override fun read(sink: Buffer, byteCount: Long): Long {
                check(!supplied) { "Tried to read beyond the blank CR before dispatch" }
                supplied = true
                return sink.writeUtf8("data: 1\r\r").size
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }
        val lines = ProviderLineReader(upstream.buffer())
        val framing = ProviderSseFraming()
        assertNull(framing.line(requireNotNull(lines.readLine())))
        assertEquals("1", framing.line(requireNotNull(lines.readLine())))
    }

    @Test fun deeplyNestedJsonFailsBeforeRecursiveDecoding() {
        val payload = "[".repeat(ProviderPayloadLimits.MAX_JSON_DEPTH + 1) + "0" + "]".repeat(ProviderPayloadLimits.MAX_JSON_DEPTH + 1)
        val failure = assertThrows(IllegalStateException::class.java) { ProviderPayloadLimits.checkedJson(payload) }
        assertTrue(failure.message.orEmpty().contains("nesting"))
        assertFalse(failure.message.orEmpty().contains(payload))
    }

    @Test fun BracketsAndEscapedQuotesInsideReasoningTextAreNotCountedAsNesting() {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("text", kotlinx.serialization.json.JsonPrimitive("\\\"" + "[{}]".repeat(10_000)))
        }.toString()
        assertSame(payload, ProviderPayloadLimits.checkedJson(payload))
    }
}
