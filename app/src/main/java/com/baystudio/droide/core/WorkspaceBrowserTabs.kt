package com.baystudio.droide.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt

data class AgentBrowserViewport(val mode: String, val width: Int, val height: Int) {
    init {
        require(mode == "desktop" || mode == "mobile") { "Use desktop or mobile" }
        require(width in 240..1920 && height in 240..1920 && width * height <= 2_304_000) {
            "Use dimensions between 240 and 1920 CSS pixels and a maximum area of 2304000 pixels"
        }
    }

    internal fun pixels(css: Int, density: Float): Int = (css * density).roundToInt().coerceAtLeast(1)

    companion object {
        val Desktop = AgentBrowserViewport("desktop", 1280, 800)
        val Mobile = AgentBrowserViewport("mobile", 390, 844)

        internal fun from(args: JsonObject, current: AgentBrowserViewport): AgentBrowserViewport {
            val mode = if ("mode" !in args) current.mode else requireNotNull((args["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull) { "Use desktop or mobile" }
            val preset = if (mode == current.mode && "mode" !in args) current else if (mode == "desktop") Desktop else Mobile
            fun dimension(key: String, fallback: Int): Int = if (key !in args) fallback else
                requireNotNull((args[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull) { "$key must be a whole number" }
            return AgentBrowserViewport(mode, dimension("width", preset.width), dimension("height", preset.height))
        }
    }
}

class WorkspaceBrowserTabs internal constructor(val agent: AgentBrowserController, val user: AgentBrowserController) {
    internal class Handoff(val sessionId: String)
    internal class Route(val browser: AgentBrowserController?, val handoff: Handoff?)
    private val shared = MutableStateFlow<Handoff?>(null)
    internal val handoff = shared.asStateFlow()

    internal fun route(tab: String?, sessionId: String?): Route {
        if (tab == null || tab == "agent") return Route(agent, null)
        val grant = shared.value?.takeIf { tab == "user" && it.sessionId == sessionId }
        return Route(user.takeIf { grant != null }, grant)
    }

    internal fun isCurrent(route: Route): Boolean = route.handoff == null || shared.value === route.handoff

    @Synchronized internal fun share(sessionId: String, stillCurrent: () -> Boolean): Boolean {
        if (sessionId.isBlank() || !stillCurrent()) return false
        if (shared.value?.sessionId != sessionId) {
            revoke()
            shared.value = Handoff(sessionId)
        }
        return true
    }

    @Synchronized internal fun revoke() {
        if (shared.value == null) return
        shared.value = null
        user.cancelSharedTabWork()
    }
}
