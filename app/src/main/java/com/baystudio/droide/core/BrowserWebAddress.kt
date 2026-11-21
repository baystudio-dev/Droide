package com.baystudio.droide.core

import java.net.URI

internal object BrowserWebAddress {
    private val schemes = setOf("http", "https")
    private val numericLabel = Regex("(?:[0-9]+|0[xX][0-9a-fA-F]+)")
    private val inlineTypes = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/avif", "image/bmp",
        "image/x-icon", "image/vnd.microsoft.icon", "application/font-woff", "application/vnd.ms-fontobject")

    /** WebView owns the path, query and fragment. Only the authority selects a network destination. */
    fun authority(raw: String): URI {
        val start = raw.indexOf("://")
        require(start in 4..5 && raw.substring(0, start).lowercase() in schemes &&
            raw.none { it == '\\' || it.code < 32 || it.code == 127 }) { "Enter a valid HTTP or HTTPS address" }
        val end = raw.indexOfAny(charArrayOf('/', '?', '#'), start + 3).let { if (it < 0) raw.length else it }
        val uri = URI(raw.substring(0, end)).parseServerAuthority()
        val host = requireNotNull(uri.host) { "Enter a complete website address" }
        require(uri.rawUserInfo == null && !uri.rawAuthority.endsWith(':') &&
            (uri.port == -1 || uri.port in 1..65535)) { "Invalid website address" }
        val labels = host.trimEnd('.').split('.')
        if (labels.all(numericLabel::matches)) require(labels.size == 4 && labels.all {
            it == "0" || (it.first() in '1'..'9' && it.all { c -> c in '0'..'9' } && it.toIntOrNull() in 1..255)
        }) { "Invalid numeric website address" }
        return uri
    }

    fun fromAddressBar(input: String): String {
        val value = input.trim()
        if (value.startsWith("https://", true) || value.startsWith("http://", true)) return value
        val candidate = "https://$value"
        val host = authority(candidate).host
        require('.' in host || AgentRepairCompletionPolicy.isLiteralLoopback(host)) { "Enter a complete website address" }
        return candidate
    }

    fun inlineResource(raw: String, headers: Map<String, String>): Boolean {
        if (!raw.startsWith("data:", ignoreCase = true)) return false
        val prefix = raw.take(128)
        val end = prefix.indexOfAny(charArrayOf(';', ','), 5)
        if (end < 0) return false
        val type = prefix.substring(5, end).lowercase()
        if (type == "image/svg+xml") return headers.any { (k, v) ->
            (k.equals("Sec-Fetch-Dest", true) && v.equals("image", true)) ||
                (k.equals("Accept", true) && v.startsWith("image/", true))
        }
        return type in inlineTypes || type.startsWith("font/") || type.startsWith("audio/") || type.startsWith("video/")
    }
}
