package com.baystudio.droide.core

import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import okhttp3.Call
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal object NineRouterProvider {
    const val ID = "9router"
    const val NAME = "9Router"
    const val LOCAL_URL = "http://127.0.0.1:20128/v1"

    val defaults get() = UserProviderDefinition(ID, NAME, LOCAL_URL)

    /** Parse canonical dotted decimal without DNS. IPv4 site-local addresses are the three RFC 1918 ranges. */
    fun privateIpv4(host: String): InetAddress? {
        if (host.length !in 7..15) return null
        val octets = host.split('.')
        if (octets.size != 4) return null
        val bytes = ByteArray(4)
        for ((index, octet) in octets.withIndex()) {
            val value = octet.toIntOrNull() ?: return null
            if (value !in 0..255 || value.toString() != octet) return null
            bytes[index] = value.toByte()
        }
        return InetAddress.getByAddress(bytes).takeIf { it.isSiteLocalAddress }
    }

    fun lanTarget(provider: AiProvider, raw: String): ValidatedNetworkTarget {
        require(provider.id == ID) { "LAN access requires the 9Router preset" }
        val uri = URI(raw.trim())
        require(uri.rawUserInfo == null && uri.rawFragment == null && uri.host != null &&
            (uri.port == -1 || uri.port in 1..65535)) { "Invalid 9Router endpoint" }
        val address = requireNotNull(privateIpv4(uri.host)) { "9Router LAN access requires a private IPv4 address" }
        val endpoint = uri.toASCIIString().toHttpUrl()
        val origin = provider.baseUrl.toHttpUrl()
        require(endpoint.scheme == origin.scheme && endpoint.host == origin.host && endpoint.port == origin.port) {
            "9Router requests must stay on the configured server and port"
        }
        return ValidatedNetworkTarget(uri.toASCIIString(), uri.host, listOf(address), nineRouterLan = true)
    }

    fun lanCall(client: OkHttpClient, target: ValidatedNetworkTarget, request: Request): Call {
        val url = request.url
        require(url == target.url.toHttpUrl()) { "9Router request changed after validation" }
        // Android cleartext rules match domain names, not CIDRs. Resolve this reserved name only
        // to the selected literal. Give the server its original Host and keep TLS on the real IP.
        val transportHost = if (url.isHttps) url.host else "${url.host.replace('.', '-')}.nine-router-lan.invalid"
        val outbound = if (url.isHttps) request else request.newBuilder()
            .url(url.newBuilder().host(transportHost).build())
            .header("Host", url.toUri().rawAuthority)
            .build()
        return client.newBuilder()
            .dns(LanDns(transportHost, target.addresses.single()))
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false)
            .build().newCall(outbound)
    }

    private data class LanDns(val host: String, val address: InetAddress) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname != host) throw UnknownHostException("Unexpected 9Router host")
            return listOf(address)
        }
    }

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
