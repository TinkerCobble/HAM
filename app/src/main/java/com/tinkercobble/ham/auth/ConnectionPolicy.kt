package com.tinkercobble.ham.auth

import java.net.URI

/** Only user-supplied HTTPS or private/local HTTP servers can receive credentials. */
object ConnectionPolicy {
    fun normalize(input: String): String {
        val uri = URI(input.trim().trimEnd('/'))
        val host = uri.host?.lowercase() ?: error("Enter a complete Home Assistant address")
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "Use a server address without credentials or a query" }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "Use the Home Assistant server root" }
        require(uri.scheme == "https" || (uri.scheme == "http" && localHost(host))) {
            "Use HTTPS for remote servers. HTTP is allowed only on a local network."
        }
        return uri.toString().trimEnd('/')
    }

    fun isLocal(server: String): Boolean {
        val host = URI(server).host?.lowercase() ?: return false
        return localHost(host)
    }

    private fun localHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local")) return true
        val p = host.split('.').map { it.toIntOrNull() }
        if (p.size != 4 || p.any { it == null || it !in 0..255 }) return false
        val a = p[0]!!; val b = p[1]!!
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
    }
}
