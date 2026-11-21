package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class Utf8StreamDecoderTest {
    @Test fun boundedSlicesCanShareAPacketAndACodePoint() {
        val bytes = "x😀漢z".toByteArray()
        val actual = StringBuilder()
        val decoder = Utf8StreamDecoder(actual::append)
        decoder.accept(bytes, 1, 2)
        assertThrows(IllegalArgumentException::class.java) { decoder.accept(bytes, 3, Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { decoder.accept(bytes, -1, 1) }
        decoder.accept(bytes, 3, 5)
        decoder.accept(bytes, bytes.size, 0)
        decoder.finish()
        assertEquals("😀漢", actual.toString())
    }

    @Test fun preservesMultibyteCharactersAcrossEveryByteBoundary() {
        val expected = "Build 日本語 selesai ✅ 😀 é\n"
        val actual = StringBuilder()
        val decoder = Utf8StreamDecoder(actual::append)
        expected.toByteArray().forEach { decoder.accept(byteArrayOf(it), 1) }
        decoder.finish()
        assertEquals(expected, actual.toString())
    }

    @Test fun preservesCharactersAcrossFullReadBufferBoundary() {
        val expected = "a".repeat(8191) + "😀" + "字".repeat(4096)
        val actual = StringBuilder()
        val decoder = Utf8StreamDecoder(actual::append)
        expected.toByteArray().asList().chunked(8192).forEach { chunk -> decoder.accept(chunk.toByteArray(), chunk.size) }
        decoder.finish()
        assertEquals(expected, actual.toString())
    }

    @Test fun finishesIncompleteInputWithReplacement() {
        val actual = StringBuilder()
        val decoder = Utf8StreamDecoder(actual::append)
        decoder.accept(byteArrayOf(0xe2.toByte(), 0x82.toByte()), 2)
        assertEquals("", actual.toString())
        decoder.finish()
        assertEquals("\uFFFD", actual.toString())
    }
}
