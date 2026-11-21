package com.baystudio.droide.core

import androidx.webkit.UserAgentMetadata
import org.junit.Assert.*
import org.junit.Test

class AgentBrowserRenderingTest {
    @Test fun desktopSendsConsistentIdentityAndKeepsTheInstalledBrowserVersion() {
        val mobile = "Mozilla/5.0 (Linux; Android 15; Pixel; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/141.0.0.0 Mobile Safari/537.36"
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
            AgentBrowserSiteMode.desktopAgent(mobile))
        val original = UserAgentMetadata.Builder().setPlatform("Android").setPlatformVersion("15")
            .setFullVersion("141.0.0.0").setArchitecture("arm").setModel("phone").setMobile(true).build()
        val desktop = AgentBrowserSiteMode.desktopHints(original)
        assertEquals("Linux", desktop.platform); assertFalse(desktop.isMobile)
        assertEquals("", desktop.model); assertEquals("", desktop.platformVersion)
        assertEquals(original.fullVersion, desktop.fullVersion)
        assertEquals("Android", original.platform); assertTrue(original.isMobile)
    }

    @Test fun densityAndPageScaleCannotTurnAPhoneCropIntoAVerifiedDesktop() {
        val target = AgentBrowserViewport.Desktop
        val phone = requireNotNull(BrowserCssViewport.parse("[427,267,426.67,266.67,3]"))
        assertFalse(phone.matches(target)); assertEquals(3f, phone.densityForSurface(1280, 800))
        assertNull(phone.densityForSurface(3840, 2400))
        val clipped = requireNotNull(BrowserCssViewport.parse("[1280,800,427,267,3]"))
        assertFalse(clipped.matches(target))
        assertTrue(requireNotNull(BrowserCssViewport.parse("[1280,800,1265,785,3]")).matches(target))
        assertTrue(requireNotNull(BrowserCssViewport.parse("[390,844,390,844,2.625]")).matches(AgentBrowserViewport.Mobile))
    }

    @Test fun invalidMeasurementsNeverDriveALayoutOrPublishAScreenshot() {
        for (raw in listOf("null","{}","[1,2]","[0,2,3,4,1]","[1,2,3,4,1e2000]"," ".repeat(200))) {
            assertNull(BrowserCssViewport.parse(raw))
        }
        assertNull(BrowserCssViewport(1280.0, 800.0, 1280.0, 800.0, 20.0).densityForSurface(25600,16000))
    }
}
