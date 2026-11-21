package com.baystudio.droide.ui

/** Content revisions control the expensive large-buffer capture. Selection and navigation still persist. */
internal class EditorRecoverySchedule {
    private var savedSignature: String? = null
    private var savedSmall = emptyMap<String, Int>()
    private var observedLarge = emptyMap<String, Int>()
    private var lastLargeChange = 0L
    private var pendingSince: Long? = null
    private var savedAt = -4_000L

    fun due(now: Long, signature: String, small: Map<String, Int>, large: Map<String, Int>): Boolean {
        if (large != observedLarge) {
            lastLargeChange = now
            observedLarge = large
        }
        if (signature == savedSignature) {
            pendingSince = null
            return false
        }
        val first = pendingSince ?: now.also { pendingSince = it }
        return large.isEmpty() || small != savedSmall || now - first >= 8_000 ||
            (now - lastLargeChange >= 4_000 && now - savedAt >= 4_000)
    }

    fun saved(now: Long, signature: String, small: Map<String, Int>) {
        savedSignature = signature
        savedSmall = small
        pendingSince = null
        savedAt = now
    }
}
