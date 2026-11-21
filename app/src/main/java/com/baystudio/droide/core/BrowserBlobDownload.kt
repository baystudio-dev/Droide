package com.baystudio.droide.core

import android.net.Uri
import android.util.Base64
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebView
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.baystudio.droide.core.BrowserBlobStream.CHUNK_BYTES
import kotlin.coroutines.resume

internal object BrowserBlobDownload {
    suspend fun copy(view: WebView, download: BrowserDownload, output: OutputStream, current: () -> Boolean) {
        val origin = requireNotNull(download.pageOrigin)
        val marker = UUID.randomUUID().toString()
        val messages = Channel<String>(1)
        val ports = withContext(Dispatchers.Main.immediate) {
            check(current()) { "Download page changed" }
            view.createWebMessageChannel()
        }
        try {
            withTimeout(15_000) {
                withContext(Dispatchers.Main.immediate) {
                    check(current()) { "Download page changed" }
                    ports[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                        override fun onMessage(port: WebMessagePort, message: WebMessage) {
                            val data = message.data
                            if (data == null || data.length > CHUNK_BYTES * 4 / 3 ||
                                !messages.trySend(data).isSuccess) messages.close(IOException("Invalid download stream"))
                        }
                    })
                    val ready = suspendCancellableCoroutine { reply ->
                        view.evaluateJavascript(BrowserBlobStream.script(download.address, marker)) { reply.resume(it) }
                    }
                    check(ready == "true" && current()) { "Download page is unavailable" }
                    view.postWebMessage(WebMessage(marker, arrayOf(ports[1])), Uri.parse(origin))
                }
            }
            val length = withTimeout(15_000) { messages.receive() }.removePrefix("size:").toLongOrNull()
            require(length != null && length >= 0) { "The download is unavailable" }
            var remaining = length
            while (remaining > 0) {
                withContext(Dispatchers.Main.immediate) {
                    check(current()) { "Download page changed" }
                    ports[0].postMessage(WebMessage("next"))
                }
                val expected = minOf(CHUNK_BYTES.toLong(), remaining).toInt()
                val message = withTimeout(15_000) { messages.receive() }
                require(message.length == (expected + 2) / 3 * 4) { "Incomplete download chunk" }
                val chunk = Base64.decode(message, Base64.NO_WRAP)
                require(chunk.size == expected) { "Incomplete download chunk" }
                output.write(chunk); remaining -= chunk.size
            }
        } finally {
            messages.cancel()
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                runCatching { ports[0].postMessage(WebMessage("close")) }
                ports.forEach { runCatching { it.close() } }
            }
        }
    }
}
