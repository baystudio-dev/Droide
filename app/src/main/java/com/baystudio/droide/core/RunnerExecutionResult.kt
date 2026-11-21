package com.baystudio.droide.core

internal data class RunnerExecutionResult(
    val output: String,
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val processStarted: Boolean = false,
) {
    val success: Boolean get() = processStarted && exitCode == 0 && !timedOut
}

internal fun boundedRunOutput(text: CharSequence, limit: Int): String {
    val marker = "\n[… output omitted …]\n"
    require(limit >= marker.length + 2)
    if (text.length <= limit) return text.toString()
    val budget = limit - marker.length
    var head = budget / 2
    var tail = text.length - (budget - head)
    if (Character.isSurrogatePair(text[head - 1], text[head])) head--
    if (Character.isSurrogatePair(text[tail - 1], text[tail])) tail++
    return buildString(limit) { append(text, 0, head); append(marker); append(text, tail, text.length) }
}
