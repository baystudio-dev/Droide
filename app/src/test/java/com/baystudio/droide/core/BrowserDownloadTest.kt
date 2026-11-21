package com.baystudio.droide.core

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowserDownloadTest {
    private val page = "https://www.example.org/page"
    private fun offer(url: String, disposition: String? = null) =
        BrowserDownload.offer(url, page, "BrowserFixture", disposition, "application/octet-stream", -1)
    private fun read(url: String, respond: (Request) -> Response): ByteArray = runBlocking {
        val output = ByteArrayOutputStream()
        val client = OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build()
        offer(url).transfer(output, { raw ->
            val uri = NetworkSecurity.validatePublicHttpsUrl(raw)
            ValidatedNetworkTarget(raw, uri.host, listOf(InetAddress.getByAddress(byteArrayOf(8,8,8,8))))
        }, { "session=fixture" }, client)
        output.toByteArray()
    }
    private fun response(request: Request, code: Int, data: ByteArray = byteArrayOf()) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(data.toResponseBody())

    @Test fun redirectsPreserveSignedParametersAndSendCookiesOnlyToTheOriginalPageOrigin() {
        val hops = mutableListOf<Request>()
        val bytes = read("https://www.example.org/report?key=a%2Bb%2Fc#part") { req ->
            hops.add(req)
            if (hops.size == 1) response(req, 302).header("Location", "https://cdn.example.net/file?sig=x%2Fy").build()
            else response(req, 200, byteArrayOf(0, -1, 9)).build()
        }
        assertArrayEquals(byteArrayOf(0, -1, 9), bytes)
        assertEquals(listOf("key=a%2Bb%2Fc","sig=x%2Fy"),hops.map { it.url.encodedQuery })
        assertEquals("session=fixture", hops[0].header("Cookie"))
        assertNull(hops[1].header("Cookie")); assertNull(hops[1].header("Referer"))
    }

    @Test fun redirectsAndPartialResponsesCannotPublishAnUnrequestedOrTruncatedFile() {
        for (location in listOf("http://www.example.org/plain","https://127.0.0.1/private","https://user@example.org/file")) {
            var requests = 0
            assertTrue(runCatching { read(page) { requests++; response(it,302).header("Location",location).build() } }.isFailure)
            assertEquals(1, requests)
        }
        for (code in listOf(204,403,404,503)) assertTrue(runCatching { read(page) { response(it,code).build() } }.isFailure)
        assertTrue(runCatching { read(page) { response(it,206,byteArrayOf(1)).header("Content-Range","bytes 2-2/3").build() } }.isFailure)
        assertArrayEquals(byteArrayOf(9), read(page) { response(it,206,byteArrayOf(9)).header("Content-Range","bytes 0-0/1").build() })
    }

    @Test fun fileNamesSourcesAndBinaryInlineDataAreHandledAsDownloads() {
        for (url in listOf("blob:https://another.example/id","file:///tmp/a","javascript:alert(1)","https://127.0.0.1/a")) {
            assertTrue(runCatching { offer(url) }.isFailure)
        }
        assertTrue(offer("blob:https://www.example.org/id").generated)
        val name = offer(page,"attachment; filename=\"../fixture.bin\"").fileName
        assertFalse(name.contains('/')); assertTrue(name.endsWith(".bin"))
        for (url in listOf("data:application/octet-stream;base64,AP8r","data:application/octet-stream,%00%FF+")) {
            val sink = ByteArrayOutputStream(); offer(url).copyInline(sink)
            assertArrayEquals(byteArrayOf(0,-1,43), sink.toByteArray())
        }
    }

    @Test fun mediaStorePublishesOnlyAfterTheCompleteFileAndRollsBackFailures() = runBlocking {
        val store = Robolectric.setupContentProvider(DownloadStore::class.java, "media")
        val context = RuntimeEnvironment.getApplication()
        offer("data:application/octet-stream;base64,AP8r").save(context) { error("Unexpected blob") }
        assertEquals(1, store.published); assertEquals(0,store.deletes)
        assertArrayEquals(byteArrayOf(0,-1,43), store.file.readBytes())
        assertTrue(runCatching { offer("blob:https://www.example.org/id").save(context) {
            it.write(8); throw java.io.IOException("Fixture interrupted")
        } }.isFailure)
        assertEquals(1, store.published); assertEquals(1,store.deletes)
        val started = CompletableDeferred<Unit>()
        val transfer = launch {
            offer("blob:https://www.example.org/pending").save(context) { out ->
                out.write(7); started.complete(Unit); awaitCancellation()
            }
        }
        started.await(); transfer.cancelAndJoin()
        assertEquals(1,store.published); assertEquals(2,store.deletes)
    }

    class DownloadStore : ContentProvider() {
        lateinit var file: File
        var published=0; var deletes=0
        override fun onCreate(): Boolean = true
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            check(values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING)==1)
            check(values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)=="Download")
            file=File.createTempFile("browser-download-",".bin",requireNotNull(context).cacheDir)
            val row = Uri.withAppendedPath(uri, "7")
            org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication().contentResolver)
                .registerOutputStreamSupplier(row) { file.outputStream() }
            return row
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            check(values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING)==0)
            published++; return 1
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int { deletes++;file.delete();return 1 }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
    }
}
