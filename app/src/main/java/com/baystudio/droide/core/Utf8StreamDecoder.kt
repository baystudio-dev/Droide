package com.baystudio.droide.core

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

internal class Utf8StreamDecoder(private val emit: (String) -> Unit) {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val pending = ByteBuffer.allocate(MAX_CHUNK_BYTES + 4)
    private val text = CharBuffer.allocate(MAX_CHUNK_BYTES + 4)

    fun accept(bytes: ByteArray, count: Int) = accept(bytes, 0, count)

    fun accept(bytes: ByteArray, offset: Int, count: Int) {
        require(offset in 0..bytes.size && count in 0..minOf(MAX_CHUNK_BYTES, bytes.size - offset))
        pending.put(bytes, offset, count)
        decode(end = false)
    }

    fun finish() = decode(end = true)

    private fun decode(end: Boolean) {
        pending.flip()
        text.clear()
        decoder.decode(pending, text, end)
        if (end) decoder.flush(text)
        pending.compact()
        text.flip()
        if (text.hasRemaining()) emit(text.toString())
    }

    companion object {
        const val MAX_CHUNK_BYTES = 8 * 1024
    }
}
