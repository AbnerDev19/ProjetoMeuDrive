package com.meudrive

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Devices authorized against this server; discovery lists only MeuDrive servers, never arbitrary LAN hosts. */
class PeerRegistry(private val now: () -> Long = { System.currentTimeMillis() }) {
    private data class Client(val id: String, val name: String, val seen: Long)
    private val clients = ConcurrentHashMap<String, Client>()
    companion object {
        const val TTL = 90_000L
        fun serverId(host: String, port: Int): String = MessageDigest.getInstance("SHA-256")
            .digest("$host:$port".toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun heartbeat(id: String, name: String): JSONObject {
        require(id.length in 8..100 && id.all { it.isLetterOrDigit() || it in "-_." }) { "Identificação de aparelho inválida" }
        val safeName = name.filter { it.code >= 32 }.trim().take(80).ifEmpty { "Navegador conectado" }
        prune()
        if (clients.size >= 100 && !clients.containsKey(id)) clients.entries.minByOrNull { it.value.seen }?.let { clients.remove(it.key) }
        clients[id] = Client(id, safeName, now())
        return JSONObject().put("ok", true)
    }
    fun list(servers: List<Peer>): JSONArray {
        prune()
        val result = JSONArray()
        servers.distinctBy { "${it.host}:${it.port}" }.forEach {
            result.put(JSONObject().put("id", serverId(it.host, it.port)).put("kind", "server")
                .put("name", it.name).put("host", it.host).put("port", it.port))
        }
        clients.values.sortedBy { it.name.lowercase() }.forEach {
            result.put(JSONObject().put("id", "client-${it.id}").put("kind", "client").put("name", it.name).put("lastSeen", it.seen))
        }
        return result
    }
    private fun prune() { val cutoff = now() - TTL; clients.entries.removeAll { it.value.seen < cutoff } }
}
