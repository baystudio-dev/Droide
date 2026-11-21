package com.baystudio.droide.core

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Invocation-local options; never shared with the next queued operation. */
internal data class BrowserActionOptions(
    val checked: Boolean? = null,
    val optionIndex: Int? = null,
    val keyStroke: String? = null,
    val endTarget: String? = null,
    val timeoutMs: Long = 4_000,
    val durationMs: Long = 350,
    val deltaX: Float? = null,
    val deltaY: Float? = null,
    val touchDrag: Boolean = false,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BrowserActionOptions>
    val hasOffset get() = deltaX != null || deltaY != null

    fun validateDrag() {
        require((endTarget != null) != hasOffset && (!hasOffset || (deltaX ?: 0f) != 0f || (deltaY ?: 0f) != 0f)) {
            "Drag needs either an end_target or a nonzero CSS pixel offset"
        }
    }

    init {
        require(listOfNotNull(deltaX, deltaY).all { it.isFinite() && it in -4096f..4096f }) { "Invalid drag offset" }
        require(timeoutMs in 100..15_000 && durationMs in 100..2_000) { "Browser action timing is out of range" }
        require(optionIndex == null || optionIndex in 0..10_000) { "Invalid select option index" }
        require(keyStroke == null || keyStroke.length in 1..80) { "Invalid browser key" }
        require(endTarget == null || endTarget.length in 1..20_000) { "Invalid drag destination" }
    }
}
