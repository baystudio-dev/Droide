package com.baystudio.droide.core

import android.os.SystemClock
import android.view.ViewConfiguration
import android.webkit.WebView
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Only target preparation is retried. Input that reached the page is never replayed. */
internal class AgentBrowserActions(
    private val view: WebView,
    private val evaluate: suspend (String) -> String,
    private val dispatch: suspend (() -> Unit) -> Unit,
    private val cancel: suspend (() -> Unit) -> Unit,
    private val cancelPageDrag: suspend (String) -> String = evaluate,
) {
    private suspend fun evaluateResult(script: String) = AgentBrowserInteraction.result(evaluate(script))

    suspend fun execute(operation: String, target: String, text: String?, submit: Boolean, options: BrowserActionOptions): JsonObject =
        try { executeAction(operation, target, text, submit, options) }
        catch (_: BrowserPointerInterruptedException) { failure("gesture_interrupted", "The page changed after pointer down. Inspect its state before further input.", true) }

    private suspend fun executeAction(operation: String, target: String, text: String?, submit: Boolean, options: BrowserActionOptions): JsonObject {
        val key = if (operation == "key") AgentBrowserNativeInput.key(requireNotNull(options.keyStroke) { "key is required" })
            else if (submit) AgentBrowserNativeInput.key("Enter") else null
        require(!submit || operation == "type") { "submit is supported only for type" }
        if (operation == "drag") options.validateDrag()
        require(operation != "swipe" || text in setOf("up", "down", "left", "right")) { "swipe requires up/down/left/right" }
        suspend fun run(op: String, selector: String) = evaluateResult(
            AgentBrowserInteraction.script(op, selector, text, submit, options.checked, options.optionIndex))
        suspend fun prepare(op: String, selector: String): JsonObject {
            val deadline = SystemClock.uptimeMillis() + options.timeoutMs
            var pinned = selector
            var previous: AgentBrowserNativeInput.Point? = null
            while (true) {
                val result = run(op, pinned)
                if (result.ok()) {
                    if (!result.flag("prepared") || result["point"] == null) return result
                    pinned = result.getValue("target").jsonPrimitive.content
                    val point = AgentBrowserNativeInput.point(result)
                    if (previous == point || previous != null && checkHit(pinned, previous).ok()) return result
                    previous = point
                } else {
                    previous = null
                    if (!result.flag("retryable") || !result["blocked_by"]?.jsonObject?.get("controls")?.jsonArray.isNullOrEmpty()) return result
                }
                if (SystemClock.uptimeMillis() >= deadline) return if (!result.ok()) result else
                    failure("unstable_target", "Target moved out of reach before input.")
                delay(48)
            }
        }
        var result = prepare(operation, target)
        if (!result.ok()) return result
        val selector = result["target"]?.jsonPrimitive?.contentOrNull ?: target
        if (result.flag("prepared") && operation != "key") {
            var from = AgentBrowserNativeInput.point(result)
            var to = from
            var destination: String? = null
            if (operation == "drag") {
                if (options.endTarget != null) {
                    val end = prepare("drag", options.endTarget)
                    if (!end.ok()) return end
                    destination = end.getValue("target").jsonPrimitive.content
                    to = AgentBrowserNativeInput.point(end)
                    val fresh = run("verify_point", selector)
                    if (!fresh.ok()) return fresh
                    from = AgentBrowserNativeInput.point(fresh)
                    val endHit = checkHit(destination, to)
                    if (!endHit.ok()) return endHit
                } else {
                    val viewport = result.getValue("viewport").jsonObject
                    val x = from.x + (options.deltaX ?: 0f) / viewport.getValue("width").jsonPrimitive.float
                    val y = from.y + (options.deltaY ?: 0f) / viewport.getValue("height").jsonPrimitive.float
                    if (x !in 0f..<1f || y !in 0f..<1f) return failure("outside_viewport", "Drag destination is outside the visible page.")
                    to = AgentBrowserNativeInput.Point(x, y)
                }
            } else if (operation == "swipe") {
                val bounds = result.getValue("bounds").jsonObject
                val vertical = text == "up" || text == "down"
                val lo = bounds.getValue(if (vertical) "top" else "left").jsonPrimitive.float
                val hi = bounds.getValue(if (vertical) "bottom" else "right").jsonPrimitive.float
                val sign = if (text == "up" || text == "left") -1 else 1
                val pos = if (sign > 0) lo + .2f * (hi - lo) else hi - .2f * (hi - lo)
                val proposed = if (vertical) from.copy(y = pos) else from.copy(x = pos)
                if (checkHit(selector, proposed).ok()) from = proposed
                val span = ((hi - lo) * .6f).coerceAtLeast(.32f)
                to = if (vertical) from.copy(y = (from.y + sign * span).coerceIn(.002f, .998f))
                    else from.copy(x = (from.x + sign * span).coerceIn(.002f, .998f))
            }
            val hit = checkHit(selector, from)
            if (!hit.ok()) return hit
            if (operation == "double_click") return doubleClick(selector, from)
            if (operation == "drag" || operation == "swipe") {
                if (from == to) return failure("no_motion", "Start and end are the same point.")
                if (operation == "drag" && !options.touchDrag && (result.flag("html_draggable") || options.hasOffset && !result.flag("native_range")))
                    return pageDrag(selector, destination, result.flag("html_draggable"), from, to, options.durationMs)
            }
            val beforeSwipe = result["position"] as? JsonObject
            AgentBrowserNativeInput.gesture(view, from, to,
                durationMs = if (operation in setOf("drag", "swipe", "hold")) options.durationMs else 64,
                fineMotion = operation == "drag", hover = operation == "hover", dispatch = dispatch, cancel = cancel)
            result = buildJsonObject { put("ok", true); put("action", operation); put("target", selector); put("dispatched", true); put("method", "native-input") }
            if (operation == "swipe" && beforeSwipe != null) {
                suspend fun position() = try { run("swipe_position", selector)["position"] as? JsonObject }
                    catch (_: BrowserSurfaceChangedException) { null }
                var after = position()
                if (after == beforeSwipe) { delay(120); after = position() }
                if (after != null && after["surface"] == beforeSwipe["surface"]) {
                    val itemChanged = beforeSwipe["item"] is JsonPrimitive && beforeSwipe["item"] != JsonNull &&
                        after["item"] != null && after["item"] != JsonNull && after["item"] != beforeSwipe["item"]
                    val moved = after["x"] != beforeSwipe["x"] || after["y"] != beforeSwipe["y"] || itemChanged
                    result = JsonObject(result + ("moved" to JsonPrimitive(moved)))
                }
            }
            if (operation == "check") {
                delay(70)
                val state = try { run("check_state", selector) } catch (_: BrowserSurfaceChangedException) {
                    return failure("document_changed", "Check input was delivered. Inspect the new page for its state.", true)
                }
                result = buildJsonObject {
                    val checked = state["checked"]?.jsonPrimitive?.booleanOrNull
                    put("ok", state.ok() && checked == options.checked); put("action", "check"); put("dispatched", true)
                    checked?.let { put("checked", it) }
                    put("note", "Input dispatched once. Read again if state is unconfirmed.")
                }
            }
        }
        if (key != null) {
            dispatch { AgentBrowserNativeInput.press(view, key) }
            result = JsonObject(result + mapOf("dispatched" to JsonPrimitive(true), "prepared" to JsonPrimitive(false)))
        }
        return result
    }

    private suspend fun checkHit(selector: String, point: AgentBrowserNativeInput.Point) =
        evaluateResult(AgentBrowserInteraction.verifyPoint(selector, point.x, point.y))

    private suspend fun doubleClick(selector: String, point: AgentBrowserNativeInput.Point): JsonObject {
        val id = UUID.randomUUID().toString()
        val watch = evaluateResult(AgentBrowserInteraction.doubleTapWatch(id, selector, true))
        if (!watch.ok()) return watch
        AgentBrowserNativeInput.gesture(view, point, durationMs = 32, dispatch = dispatch, cancel = cancel)
        try {
            delay((ViewConfiguration.getDoubleTapTimeout() / 4L).coerceIn(48, 80))
            if (!checkHit(selector, point).ok()) return failure("target_changed", "First tap delivered. The target moved or disappeared.", true)
            AgentBrowserNativeInput.gesture(view, point, durationMs = 32, dispatch = dispatch, cancel = cancel)
            delay(ViewConfiguration.getDoubleTapTimeout().toLong() + 16)
            return evaluateResult(AgentBrowserInteraction.doubleTapWatch(id, selector, false))
        } catch (changed: BrowserSurfaceChangedException) {
            return failure("document_changed", "The page changed during the double-click. Read its current state.", true)
        }
    }

    private suspend fun pageDrag(source: String, destination: String?, html: Boolean,
        from: AgentBrowserNativeInput.Point, to: AgentBrowserNativeInput.Point, duration: Long): JsonObject {
        val id = UUID.randomUUID().toString()
        suspend fun phase(name: String, fraction: Float) = evaluateResult(AgentBrowserDrag.script(id, name, source, destination,
            html, from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction))
        val start = phase("start", 0f)
        if (!start.ok()) return start
        var finished = false
        try {
            val steps = (duration / 32).toInt().coerceIn(2, 12)
            val began = SystemClock.uptimeMillis()
            for (step in 1..steps) {
                delay((began + duration * step / steps - SystemClock.uptimeMillis()).coerceAtLeast(0))
                val move = phase("move", step.toFloat() / steps)
                if (!move.ok()) return move
            }
            return phase("end", 1f).also { finished = true }
        } catch (changed: BrowserSurfaceChangedException) {
            return failure("document_changed", "Drag started and the page changed. Read its current state.", true)
        } finally {
            if (!finished) withContext(NonCancellable) {
                runCatching { cancelPageDrag(AgentBrowserDrag.script(id, "cancel", source, destination, html, from.x, from.y)) }
            }
        }
    }

    private fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.booleanOrNull == true
    private fun JsonObject.ok() = flag("ok")
    private fun failure(code: String, message: String, dispatched: Boolean = false) = buildJsonObject {
        put("ok", false); put("code", code); put("message", message); put("dispatched", dispatched)
    }
}
