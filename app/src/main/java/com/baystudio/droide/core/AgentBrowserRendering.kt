package com.baystudio.droide.core

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

internal class AgentBrowserSiteMode(private val view: WebView) {
    private val settings = view.settings
    private val mobileAgent = WebSettings.getDefaultUserAgent(view.context) ?: settings.userAgentString
    private val mobileZoom = settings.textZoom
    private val mobileLayout = settings.layoutAlgorithm
    private val originalHints = if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA))
        WebSettingsCompat.getUserAgentMetadata(settings) else null
    var mode: String? = null
        private set

    fun apply(requested: String) {
        if (requested == mode) return
        val desktop = requested == "desktop"
        val agent = if (desktop && mobileAgent != null) desktopAgent(mobileAgent) else mobileAgent
        val hints = originalHints?.let { if (desktop) desktopHints(it) else it }
        // WebView takes a physical percentage here. One CSS pixel at default page zoom spans one display dip.
        val density = (view.parent as? AgentBrowserViewportSurface)?.rendererDensity ?: view.resources.displayMetrics.density
        view.setInitialScale(if (desktop) (100 * density).roundToInt() else 0)
        if (hints != null) WebSettingsCompat.setUserAgentMetadata(settings, hints)
        if (settings.userAgentString != agent) settings.userAgentString = agent
        settings.textZoom = if (desktop) 100 else mobileZoom
        (if (desktop) WebSettings.LayoutAlgorithm.NORMAL else mobileLayout)?.let { settings.layoutAlgorithm = it }
        mode = requested
    }

    companion object {
        internal fun desktopAgent(mobile: String): String = mobile
            .replaceFirst(Regex("\\([^)]*Android[^)]*\\)", RegexOption.IGNORE_CASE), "(X11; Linux x86_64)")
            .replace(" Version/4.0", "").replace(" Mobile Safari/", " Safari/")

        internal fun desktopHints(original: UserAgentMetadata): UserAgentMetadata =
            UserAgentMetadata.Builder(original).setPlatform("Linux").setMobile(false)
                .setArchitecture("x86").setBitness(64).setModel("").setPlatformVersion("").setWow64(false).build()
    }
}

internal interface AgentBrowserViewportSurface {
    val rendererDensity: Float
    fun useRendererDensity(density: Float)
}

internal data class BrowserCssViewport(val width: Double, val height: Double, val visibleWidth: Double,
    val visibleHeight: Double, val density: Double) {

    fun matches(profile: AgentBrowserViewport): Boolean =
        abs(width - profile.width) <= 1 && abs(height - profile.height) <= 1 &&
            visibleWidth in (width - 20)..(width + 1) && visibleHeight in (height - 20)..(height + 1)

    fun densityForSurface(nativeWidth: Int, nativeHeight: Int): Float? =
        density.toFloat().takeIf { density in 0.5..6.0 &&
            abs(nativeWidth - width * density) <= density * 2 && abs(nativeHeight - height * density) <= density * 2 }

    companion object {
        const val PROBE = "[innerWidth,innerHeight,window.visualViewport?.width||innerWidth," +
            "window.visualViewport?.height||innerHeight,devicePixelRatio]"

        fun parse(result: String): BrowserCssViewport? {
            if (result.length > 192) return null
            val values = (runCatching { Json.parseToJsonElement(result) }.getOrNull() as? JsonArray)
                ?.takeIf { it.size == 5 }?.map { ((it as? JsonPrimitive)?.doubleOrNull)
                    ?.takeIf { n -> n.isFinite() && n > 0 } ?: return null } ?: return null
            return BrowserCssViewport(values[0], values[1], values[2], values[3], values[4])
        }
    }
}
