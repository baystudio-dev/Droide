package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.net.Uri
import android.webkit.DownloadListener
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowserWebCompatibilityTest {
    private class Page(context: Context): WebView(context) {
        var address = "https://example.org/start"
        var downloads: DownloadListener? = null
        override fun getUrl() = address
        override fun setDownloadListener(listener: DownloadListener?) { downloads = listener }
    }

    private fun request(url: String, main: Boolean = true, headers: Map<String,String> = emptyMap()) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = true
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = headers
    }

    private fun withPage(block: (AgentBrowserController, Page) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        ApplicationWorkAdmission.initialize(context); ApplicationWorkAdmission.reopenFromUser()
        val root = Files.createTempDirectory("browser-www").toFile()
        val controller = AgentBrowserController(root)
        val view = Page(context); controller.configure(view)
        val binding = controller.attach(view); controller.setSurfacePresented(binding,view,true)
        try { block(controller,view) } finally { controller.detach(binding,view,false); view.destroy(); root.deleteRecursively() }
    }

    @Test fun wwwAndRedirectsRetainTheirOwnExactOriginsAndOpaqueQuery() = withPage { _, view ->
        val raw = "https://www.example.org/feed/items?filters={hd}&q=a%2Fb%2Bc#next"
        assertEquals(raw,BrowserWebAddress.fromAddressBar(raw.removePrefix("https://")))
        assertEquals(raw,BrowserWebAddress.fromAddressBar(raw))
        assertEquals("https://www.example.org",BrowserWebAddress.authority(raw).toString())
        assertFalse(view.webViewClient.shouldOverrideUrlLoading(view, request(raw)))
        assertNotEquals(BrowserSiteSession.origin(raw), BrowserSiteSession.origin(raw.replace("www.","")))
        assertEquals("https://www.example.org:443", BrowserSiteSession.origin(raw))
        assertEquals(AgentRepairCompletionPolicy.BrowserTargetClass.HTTPS_CANDIDATE,AgentRepairCompletionPolicy.classifyBrowserTarget(raw))
        val cdn="https://8.8.8.8/assets/{key}?sig=a%2Bb#icon"
        assertNull(view.webViewClient.shouldInterceptRequest(view,request(cdn,false)))
        for (bad in listOf("https://www.example.org@127.0.0.1/x", "https://www.example.org\\@127.0.0.1", "https://0177.0.0.1/x",
            "https://www.example.org:99999/x", "file:///etc/passwd", "data:text/html,test", "https://www.example.org/path\nnew")) {
            assertTrue(bad,runCatching { BrowserWebAddress.authority(bad) }.isFailure)
        }
    }

    @Test fun webViewOwnsInlineMediaButTopLevelDataAndDocumentResourcesStayRestricted() = withPage { _,view ->
        val svg="data:image/svg+xml,%3Csvg/%3E"
        fun blocked(url: String, main: Boolean = false, headers: Map<String,String> = emptyMap()) =
            view.webViewClient.shouldInterceptRequest(view,request(url,main,headers))?.statusCode
        assertNull(blocked("data:image/png;base64,iVBORw0KGgo="))
        assertNull(blocked("data:font/woff2;base64,AAAA"))
        assertNull(blocked(svg,headers=mapOf("Sec-Fetch-Dest" to "image")))
        assertEquals(403,blocked(svg,headers=mapOf("Accept" to "text/html")))
        assertEquals(403,blocked("data:text/html,<p>page</p>"))
        assertEquals(403,blocked("data:image/png;base64,AAAA",true))
        assertEquals(403,blocked("https://192.168.1.2/media"))
    }

    @Test fun failureBeforeCommitShowsCurrentHostAndIgnoresStaleOrSubresourceErrors() = withPage { ctrl,view ->
        val target="https://www.example.org/foryou?q=private"
        view.webViewClient.onPageStarted(view,target,null)
        val unavailable=WebResourceResponse("text/plain","UTF-8",503,"Unavailable", emptyMap(),null)
        view.webViewClient.onReceivedHttpError(view,request(view.address),unavailable)
        view.webViewClient.onReceivedHttpError(view,request(target,false),unavailable)
        assertNull(ctrl.navigation.value.lastError)
        view.webViewClient.onReceivedHttpError(view,request(target),unavailable)
        assertEquals("HTTP 503 · www.example.org",ctrl.navigation.value.lastError)
        assertFalse(ctrl.navigation.value.loading)
    }

    @Test fun downloadProposalRequiresVisibleHumanAcceptanceAndCannotFollowTheNextDocument() = withPage { ctrl,view ->
        val listener=requireNotNull(view.downloads)
        fun offer(url: String) = listener.onDownloadStart(url,null,null,"application/pdf",7)
        offer("https://www.example.org/doc.pdf")
        val first=requireNotNull(ctrl.pendingDownload.value)
        offer("https://another.example/other.pdf")
        assertSame(first,ctrl.pendingDownload.value)
        assertTrue(ctrl.acceptDownload(first,view)); assertFalse(ctrl.acceptDownload(first,view))
        offer("https://www.example.org/two.pdf")
        val stale=requireNotNull(ctrl.pendingDownload.value)
        view.webViewClient.onPageStarted(view,"https://www.example.org/next",null)
        assertNull(ctrl.pendingDownload.value); assertFalse(ctrl.acceptDownload(stale,view))
        ctrl.setApplicationVisible(false); offer("https://www.example.org/hidden.pdf")
        assertNull(ctrl.pendingDownload.value)
    }
}
