package com.baystudio.droide.core

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.URLUtil
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal class BrowserDownload private constructor(
    val address: String, val pageOrigin: String?, val fileName: String, val mime: String,
    val length: Long, val userAgent: String?, val host: String, val generated: Boolean,
) {
    companion object {
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .readTimeout(30, TimeUnit.SECONDS).build()
        private val invalidName = Regex("""[\\/:*?"<>|\p{Cc}\p{Cf}]""")
        private val mimePattern = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
        private const val INLINE_LIMIT = 8 * 1024 * 1024

        fun offer(url: String, page: String, agent: String?, disposition: String?, contentType: String?, bytes: Long): BrowserDownload {
            val origin = BrowserSiteSession.origin(page)
            val blob = url.startsWith("blob:", true)
            val data = url.startsWith("data:", true)
            require(url.length in 1..(if (data) INLINE_LIMIT else 8_192)) { "Download link exceeds the supported size" }
            val host = when {
                blob -> {
                    require(origin != null && BrowserSiteSession.origin(url.substring(5)) == origin) { "Open this download in its original tab" }
                    BrowserWebAddress.authority(page).host
                }
                data -> {
                    require(origin != null && url.indexOf(',') in 5..255) { "Invalid inline download" }
                    BrowserWebAddress.authority(page).host
                }
                else -> NetworkSecurity.validatePublicHttpsUrl(BrowserWebAddress.authority(url).toString()).host
            }
            val mime = contentType?.substringBefore(';')?.trim()?.takeIf(mimePattern::matches) ?: "application/octet-stream"
            val guessUrl = if (blob || data) "https://example.invalid/download" else url
            val name = URLUtil.guessFileName(guessUrl, disposition?.take(2048), mime)
                .replace(invalidName, "_").trim().trim('.').take(160).ifBlank { "download" }
            val ua = agent?.takeIf { it.length in 1..1024 && it.all { ch -> ch.code in 32..126 } }
            return BrowserDownload(url, origin, name, mime, bytes, ua, host, blob || data)
        }

        internal fun cookieFor(url: HttpUrl, pageOrigin: String?, lookup: (String) -> String?): String? {
            if (pageOrigin == null || BrowserSiteSession.origin(url.toString()) != pageOrigin) return null
            return lookup(url.toString())?.takeIf { it.isNotBlank() && '\r' !in it && '\n' !in it }
        }
    }

    suspend fun save(context: Context, copyBlob: suspend (OutputStream) -> Unit) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val metadata = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, metadata) ?: throw IOException("Cannot create download")
        var published = false
        try {
            val stream = resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot open download destination")
            stream.buffered().use { output ->
                when {
                    address.startsWith("blob:", true) -> copyBlob(output)
                    address.startsWith("data:", true) -> copyInline(output)
                    else -> transfer(output)
                }
            }
            currentCoroutineContext().ensureActive()
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
                "Android could not finish saving the download"
            }
            published = true
        } finally {
            if (!published) resolver.delete(uri, null, null)
        }
    }

    internal fun copyInline(output: OutputStream) {
        val comma = address.indexOf(',')
        val encoded = address.substring(comma + 1)
        // A literal plus is data. Percent escapes represent bytes even for binary attachments.
        val bytes = java.io.ByteArrayOutputStream()
        var start = 0
        while (start < encoded.length) {
            val escape = encoded.indexOf('%', start).let { if (it < 0) encoded.length else it }
            bytes.write(encoded.substring(start, escape).toByteArray(Charsets.UTF_8))
            if (escape == encoded.length) break
            require(escape + 2 < encoded.length) { "Incomplete file data" }
            val hi = encoded[escape + 1].digitToIntOrNull(16)
            val lo = encoded[escape + 2].digitToIntOrNull(16)
            require(hi != null && lo != null) { "Invalid file data" }
            bytes.write(hi * 16 + lo)
            start = escape + 3
        }
        if (address.substring(5, comma).endsWith(";base64", true)) output.write(Base64.decode(bytes.toByteArray(), Base64.DEFAULT))
        else bytes.writeTo(output)
    }

    internal suspend fun transfer(output: OutputStream,
        validate: (String) -> ValidatedNetworkTarget = NetworkSecurity::validatePublicHttpsTarget,
        cookies: (String) -> String? = { CookieManager.getInstance().getCookie(it) },
        http: OkHttpClient = client,
    ) {
        var url = address.toHttpUrl().newBuilder().fragment(null).build()
        repeat(10) {
            currentCoroutineContext().ensureActive()
            val approved = validate(BrowserWebAddress.authority(url.toString()).toString())
            val request = Request.Builder().url(url).apply {
                userAgent?.let { header("User-Agent", it) }
                cookieFor(url, pageOrigin, cookies)?.let { header("Cookie", it) }
            }.build()
            val next = http.newBuilder().dns(NetworkSecurity.pinnedDns(approved)).build()
                .newCall(request).withCancellableResponse { response ->
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        val location = response.header("Location") ?: throw IOException("Download redirect has no destination")
                        val target = url.resolve(location) ?: throw IOException("Invalid download redirect")
                        require(target.isHttps && target.username.isEmpty() && target.password.isEmpty()) { "Unsupported download redirect" }
                        return@withCancellableResponse target.newBuilder().fragment(null).build()
                    }
                    if (!response.isSuccessful || response.code in setOf(204, 205)) throw IOException("Download returned HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Download has no response body")
                    val completeRange = if (response.code == 206) {
                        val range = Regex("bytes 0-([0-9]+)/([0-9]+)").matchEntire(response.header("Content-Range").orEmpty())
                        val end = range?.groupValues?.get(1)?.toLongOrNull()
                        val total = range?.groupValues?.get(2)?.toLongOrNull()
                        if (total == null || total <= 0 || end != total - 1) throw IOException("Download returned an incomplete range")
                        total
                    } else null
                    val actual = body.byteStream().copyTo(output, 64 * 1024)
                    if ((body.contentLength() >= 0 && actual != body.contentLength()) || (completeRange != null && actual != completeRange)) throw IOException("Download was interrupted")
                    null
                }
            if (next == null) return
            url = next
        }
        throw IOException("Too many download redirects")
    }
}
