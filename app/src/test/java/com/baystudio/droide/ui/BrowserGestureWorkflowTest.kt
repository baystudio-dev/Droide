package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import android.webkit.WebView
import android.webkit.ValueCallback
import android.view.View
import com.baystudio.droide.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class, qualifiers="w400dp-h760dp-xhdpi")
class BrowserGestureWorkflowTest {
    @Test fun gesturesAndReadsUseAvailableContentWhileScreenshotsVerifyTheDesktopFrame() {
        val app=RuntimeEnvironment.getApplication()
        ApplicationWorkAdmission.initialize(app);ApplicationWorkAdmission.reopenFromUser()
        val root=java.nio.file.Files.createTempDirectory("css-controller").toFile()
        val controller=AgentBrowserController(root,initialViewport=AgentBrowserViewport.Desktop)
        val caller=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val url="http://localhost:8934/fixture"
        var metrics="[640,400,640,400,2]";var reads=0;var actions=0;var domReady=true
        val browser=object:WebView(app) {
            override fun getUrl()=url
            override fun evaluateJavascript(script:String, callback:ValueCallback<String>?) {
                if(script==BrowserCssViewport.PROBE) callback?.onReceiveValue(metrics)
                else if(script.contains("const a=")) {actions++;callback?.onReceiveValue("""{"ok":true,"action":"scroll","moved":true,"before":{"y":0},"after":{"y":400}}""")}
                else if(!domReady) callback?.onReceiveValue("null")
                else {reads++;callback?.onReceiveValue("""{"ok":true,"url":"$url","text":"checked"}""")}
            }
        }
        controller.bindOpenRequest {}
        controller.configure(browser);val binding=controller.attach(browser)
        val activity=mountBrowserTestSurface(browser)
        val width=2560;val height=1600
        browser.forceLayout()
        browser.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        browser.layout(0,0,width,height);browser.left=0;browser.top=0;browser.right=width;browser.bottom=height
        controller.setSurfaceStarted(binding,browser,true);controller.setSurfacePresented(binding,browser,true,true)
        fun run(op:String):Result<String> {
            val pending=caller.async {runCatching {controller.execute(op, allowLoopback=true)}}
            val end=System.nanoTime()+7_000_000_000L
            while(!pending.isCompleted&&System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(2)}
            assertTrue("unfinished $op",pending.isCompleted);return runBlocking{pending.await()}
        }
        try {
            val read=Json.parseToJsonElement(run("read").getOrThrow()).jsonObject
            assertEquals(640,read.getValue("css_viewport_width").jsonPrimitive.int)
            assertEquals(1,reads)
            domReady=false
            val moved=Json.parseToJsonElement(run("scroll").getOrThrow()).jsonObject
            assertTrue(moved.getValue("success").jsonPrimitive.boolean)
            assertTrue(moved.getValue("page_pending").jsonPrimitive.boolean)
            assertEquals(1,actions)
            domReady=true
            metrics="[1280,800,1280,800,2]"
            assertEquals(1280,Json.parseToJsonElement(run("read").getOrThrow()).jsonObject["css_viewport_width"]?.jsonPrimitive?.int)
            val status=Json.parseToJsonElement(run("status").getOrThrow()).jsonObject
            assertTrue(status.getValue("viewport_verified").jsonPrimitive.boolean)
            metrics="[1280,800,640,400,2]"
            val screenshot=run("screenshot").exceptionOrNull()
            assertTrue("capture="+screenshot,screenshot?.message?.contains("CSS px")==true)
            assertFalse(Json.parseToJsonElement(run("status").getOrThrow()).jsonObject.getValue("viewport_verified").jsonPrimitive.boolean)
        } finally {caller.cancel();controller.detach(binding,browser,false);activity.pause().stop().destroy();browser.destroy();root.deleteRecursively()}
    }
}
