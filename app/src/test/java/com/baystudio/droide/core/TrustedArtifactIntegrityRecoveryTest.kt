package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TrustedArtifactIntegrityRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "verified root filesystem".encodeToByteArray()
    private val damaged = bytes.copyOf().apply { this[0] = 0 }
    private val spec = TrustedArtifactSpec("image", "https://1.1.1.1/series/image",
        bytes.toByteString().sha256().hex(), "image.tar.gz", bytes.size.toLong(), bytes.size.toLong())
    private val recoveryUrl = "https://1.1.1.1/version/image"
    private val requests = mutableListOf<Request>()

    @Test fun singleDamagedByteGetsOneCleanRequestAndOnlyVerifiedBytesReachTheInstaller() = runBlocking {
        val download = downloader { request, number ->
            request.reply((if (number == 1) damaged else bytes).toResponseBody())
        }
        var installations = 0
        download.withArtifact(spec, recoveryUrl) { file ->
            installations++
            assertArrayEquals(bytes, file.readBytes())
        }
        assertArrayEquals(bytes, download.recover().readBytes())
        assertEquals(1, installations)
        assertEquals(listOf(spec.url, recoveryUrl), requests.map { it.url.toString() })
        assertEquals("identity", requests[1].header("Accept-Encoding"))
        assertTrue(requests[1].cacheControl.noCache)
        assertTrue(requests[1].cacheControl.noTransform)
        assertNull(requests[1].header("Range"))
        assertNull(requests[1].header("If-Range"))
    }

    @Test fun persistentMismatchStopsCleanlyAndManualRetryCanSucceed() = runBlocking {
        var healthy = false
        val download = downloader { request, _ -> request.reply((if (healthy) bytes else damaged).toResponseBody()) }
        val failure = runCatching { download.withArtifact(spec, recoveryUrl) { fail("Unverified bytes reached installer") } }.exceptionOrNull()
        assertTrue(failure is ArtifactIntegrityException)
        assertEquals(2, requests.size)
        assertNull(download.cached(spec))
        assertTrue(temporary.root.resolve("trusted-artifacts").listFiles()!!.isEmpty())
        healthy = true
        assertArrayEquals(bytes, download.recover().readBytes())
        assertEquals(3, requests.size)
        assertEquals(spec.url, requests.last().url.toString())
        assertNull(requests.last().header("Range"))
    }

    @Test fun interruptedRecoveryResumesOnlyAtTheUrlThatSuppliedItsBytes() = runBlocking {
        val offset = 8
        val download = downloader { request, number ->
            when (number) {
                1 -> request.reply(damaged.toResponseBody())
                2 -> request.reply(Buffer().write(bytes, 0, offset).asResponseBody()) { header("ETag", "\"version1\"") }
                else -> request.reply(bytes.copyOfRange(offset, bytes.size).toResponseBody(), 206) {
                    header("ETag", "\"version1\"")
                    header("Content-Range", "bytes $offset-${bytes.lastIndex}/${bytes.size}")
                }
            }
        }
        assertTrue(runCatching { download.recover() }.exceptionOrNull() is IOException)
        assertArrayEquals(bytes, download.recover().readBytes())
        assertEquals(listOf(spec.url, recoveryUrl, recoveryUrl), requests.map { it.url.toString() })
        assertEquals("bytes=$offset-", requests.last().header("Range"))
        assertEquals("\"version1\"", requests.last().header("If-Range"))
    }

    @Test fun interruptedFullReplacementCannotAttachItsNewValidatorToOldBytes() = runBlocking {
        val download = downloader { request, number ->
            if (number == 1) request.reply(Buffer().write(damaged, 0, 6).asResponseBody()) { header("ETag", "\"old\"") }
            else request.reply(bytes.toResponseBody()) { header("ETag", "\"replacement\"") }
        }
        assertTrue(runCatching { download.recover() }.exceptionOrNull() is IOException)
        val interrupted = runCatching { download.recover() {
            if (it.phase == TrustedArtifactDownloadPhase.AWAITING_BODY) throw CancellationException("Stopped")
        } }.exceptionOrNull()
        assertTrue(interrupted is CancellationException)
        assertEquals(2, requests.size)
        assertEquals("bytes=6-", requests[1].header("Range"))
        assertArrayEquals(bytes, download.recover().readBytes())
        assertEquals(3, requests.size)
        assertNull(requests[2].header("Range"))
        assertNull(requests[2].header("If-Range"))
    }

    @Test fun wrongDeclaredSizeCanRecoverWithoutRelaxingTheSizePin() = runBlocking {
        val download = downloader { request, number ->
            request.reply((if (number == 1) byteArrayOf(1) else bytes).toResponseBody())
        }
        assertArrayEquals(bytes, download.recover().readBytes())
        assertEquals(2, requests.size)
    }

    @Test fun oversizedChunkedResponseIsDiscardedBeforeRecovery() = runBlocking {
        val download = downloader { request, number ->
            val body = if (number == 1) Buffer().write(bytes).writeByte(0).asResponseBody() else bytes.toResponseBody()
            request.reply(body)
        }
        assertArrayEquals(bytes, download.recover().readBytes())
        assertEquals(2, requests.size)
        assertNull(requests.last().header("Range"))
    }

    @Test fun artifactsWithoutARecoveryUrlKeepTheirSingleFullRequest() = runBlocking {
        val download = downloader { request, _ -> request.reply(damaged.toResponseBody()) }
        assertTrue(runCatching { download.download(spec) }.exceptionOrNull() is ArtifactIntegrityException)
        assertEquals(1, requests.size)
    }

    @Test fun recoveryCannotChangeTheApprovedHttpsOrigin() = runBlocking {
        val download = downloader { request, _ -> request.reply(bytes.toResponseBody()) }
        for (url in listOf("http://1.1.1.1/version", "https://8.8.8.8/version", "https://1.1.1.1:8443/version")) {
            assertTrue(runCatching { download.withArtifact(spec, url) {} }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(requests.isEmpty())
        download.withArtifact(spec, "https://1.1.1.1:443/version") { assertArrayEquals(bytes, it.readBytes()) }
    }

    private suspend fun TrustedArtifactDownloader.recover(onProgress: (TrustedArtifactDownloadProgress) -> Unit = {}): File =
        withArtifact(spec, recoveryUrl, onProgress) { it }

    private fun downloader(reply: (Request, Int) -> Response): TrustedArtifactDownloader {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = temporary.root
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            reply(request, requests.size)
        }.build()
        return TrustedArtifactDownloader(context, client)
    }

    private fun Request.reply(body: ResponseBody, code: Int = 200, headers: Response.Builder.() -> Unit = {}): Response =
        Response.Builder().request(this).protocol(Protocol.HTTP_1_1).code(code).message("OK").body(body).apply(headers).build()
}
