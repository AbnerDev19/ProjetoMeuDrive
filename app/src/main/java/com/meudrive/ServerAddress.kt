package com.meudrive

import java.net.URI

/** Explicit server origin. Never adds :8080 to an HTTPS site or forwards a supplied URL path. */
data class ServerAddress(val origin: String, val host: String, val secure: Boolean) {
    val privateVpn: Boolean get() {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true } && parts[0] == "100" && (parts[1].toIntOrNull() ?: 0) in 64..127
    }
    fun accepts(requestUrl: String): Boolean = try {
        val request = URI(requestUrl)
        val configured = URI(origin)
        fun port(u: URI) = if (u.port >= 0) u.port else if (u.scheme.equals("https", true)) 443 else 80
        request.userInfo == null && request.scheme.equals(configured.scheme, true) &&
            request.host != null && request.host.equals(configured.host, true) && port(request) == port(configured)
    } catch (_: Exception) { false }
    companion object {
        fun parse(raw: String): ServerAddress {
            val value = raw.trim()
            require(value.isNotEmpty()) { "Informe o endereço do servidor" }
            val u = try { URI(if (value.contains("://")) value else "http://$value") }
            catch (_: Exception) { throw IllegalArgumentException("Endereço inválido") }
            val scheme = u.scheme?.lowercase()
            require(scheme == "http" || scheme == "https") { "Use http:// ou https://" }
            require(u.host != null && u.userInfo == null && u.fragment == null && u.rawQuery == null) { "Endereço inválido; use somente o endereço do servidor" }
            require(u.path.isNullOrEmpty() || u.path == "/") { "Cole a raiz do site, sem caminho de arquivo" }
            require(u.port == -1 || u.port in 1..65535) { "Porta inválida" }
            val port = when {
                u.port == -1 && scheme == "http" -> 8080
                u.port == 443 && scheme == "https" -> -1
                else -> u.port
            }
            val origin = URI(scheme, null, u.host.lowercase(), port, null, null, null).toASCIIString()
            return ServerAddress(origin, u.host.lowercase(), scheme == "https")
        }
    }
}
