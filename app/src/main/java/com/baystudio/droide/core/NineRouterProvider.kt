package com.baystudio.droide.core

import java.net.URI

internal object NineRouterProvider {
    const val ID = "9router"
    const val NAME = "9Router"
    const val LOCAL_URL = "http://127.0.0.1:20128/v1"

    val defaults get() = UserProviderDefinition(ID, NAME, LOCAL_URL)

    fun baseUrl(url: String, uri: URI): String {
        val path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.isEmpty()) return "$url/v1"
        for (suffix in listOf("/chat/completions", "/models")) {
            if (path == suffix) return url.removeSuffix(suffix) + "/v1"
            if (path.endsWith(suffix)) {
                val base = url.removeSuffix(suffix)
                require(!base.endsWith("/models") && !base.endsWith("/chat/completions")) { "Enter the server URL with one API path." }
                return base
            }
        }
        return url
    }
}
