package com.meudrive

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

/** Bounded server-to-server copies. Credentials remain on the source server. */
class PeerOperations(
    private val resolve: (String) -> File?,
    private val peers: () -> List<Peer>,
    private val key: () -> String?,
    private val sender: () -> String
) : AutoCloseable {
    private class RemoteFailure(val code: Int, message: String) : Exception(message)
    private class Job(val id: String, val target: Peer, val paths: List<String>) {
        var state = "queued"
        var completed = 0
        val results = mutableListOf<JSONObject>()
        var error: String? = null
        @Synchronized fun snapshot(): JSONObject = JSONObject().put("id", id).put("status", state).put("total", paths.size)
            .put("completed", completed).put("target", target.name).put("results", JSONArray(results.map { JSONObject(it.toString()) }))
            .apply { error?.let { put("error", it) } }
    }
    private val jobs = ConcurrentHashMap<String, Job>()
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1)) { task ->
        Thread(task, "meudrive-peer-copy").apply { isDaemon = true }
    }
    @Volatile private var closed = false

    fun selected(raw: String): List<String> {
        val values = JSONArray(raw)
        require(values.length() in 1..200) { "Selecione entre 1 e 200 arquivos" }
        return (0 until values.length()).map {
            require(values.get(it) is String) { "Caminho inválido" }
            values.getString(it).also { path -> require(path.length <= 4096) { "Caminho longo demais" } }
        }.distinct()
    }
    fun deleteBatch(paths: List<String>): JSONObject {
        require(paths.size in 1..200) { "Seleção inválida" }
        val results = JSONArray()
        var all = true
        paths.distinct().forEach { path ->
            val result = JSONObject().put("path", path)
            try {
                val file = resolve(path) ?: throw IllegalArgumentException("Caminho fora da pasta compartilhada")
                require(path.isNotBlank() && file.isFile) { "Selecione somente arquivos; pastas não são excluídas em lote" }
                check(file.delete()) { "Não foi possível excluir" }
                result.put("ok", true)
            } catch (e: Exception) { all = false; result.put("ok", false).put("error", e.message ?: "Não foi possível excluir") }
            results.put(result)
        }
        return JSONObject().put("ok", all).put("results", results)
    }
    @Synchronized fun send(targetId: String, paths: List<String>): JSONObject {
        check(!closed) { "Servidor encerrado" }
        require(paths.size in 1..200) { "Seleção inválida" }
        val target = peers().firstOrNull { PeerRegistry.serverId(it.host, it.port) == targetId }
            ?: throw IllegalArgumentException("Destino não encontrado. Atualize Minha rede e escolha um servidor MeuDrive")
        require(target.port in 1..65535 && validAddress(target.host)) { "Endereço do destino inválido" }
        require(!key().isNullOrBlank()) { "Defina a senha da casa" }
        val selected = paths.distinct()
        selected.forEach { path -> require(!path.isBlank() && resolve(path)?.isFile == true) { "Selecione somente arquivos disponíveis na pasta compartilhada" } }
        if (jobs.size >= 20) jobs.entries.filter { synchronized(it.value) { it.value.state in setOf("done", "failed") } }.take(jobs.size - 19).forEach { jobs.remove(it.key) }
        val job = Job(UUID.randomUUID().toString(), target, selected)
        jobs[job.id] = job
        try { executor.execute { run(job) } }
        catch (_: RejectedExecutionException) { jobs.remove(job.id); throw IllegalStateException("Já há envios suficientes na fila. Aguarde o envio atual") }
        return job.snapshot()
    }
    fun status(id: String): JSONObject = (jobs[id] ?: throw IllegalArgumentException("Envio não encontrado; o servidor pode ter sido reiniciado")).snapshot()
    private fun run(job: Job) {
        synchronized(job) { job.state = "running" }
        var failed = false
        for (path in job.paths) {
            val result = JSONObject().put("path", path)
            try {
                check(!closed && !Thread.currentThread().isInterrupted) { "Servidor encerrado" }
                val destName = copy(job.target, path)
                synchronized(job) { job.completed++ }
                result.put("ok", true).put("name", destName)
            } catch (e: Exception) {
                failed = true
                val message = e.message ?: "Conexão interrompida"
                result.put("ok", false).put("error", message)
                synchronized(job) { job.error = message }
            }
            synchronized(job) { job.results.add(result) }
            if (closed) break
        }
        synchronized(job) { job.state = if (failed || closed) "failed" else "done" }
    }
    private fun copy(peer: Peer, path: String): String {
        val file = resolve(path) ?: throw IllegalArgumentException("Arquivo indisponível")
        require(file.isFile) { "Arquivo indisponível" }
        val size = file.length(); val modified = file.lastModified(); val canonical = file.canonicalPath
        val secret = key() ?: throw IllegalStateException("Senha não configurada")
        val host = if (peer.host.contains(':')) "[${peer.host}]" else peer.host
        val base = "http://$host:${peer.port}"
        var state = json(base, secret, "/api/transfer/start", "path=Transferencias&name=${enc(file.name)}&size=$size&from=${enc(sender())}")
        RandomAccessFile(file, "r").use { input ->
            var attempts = 0
            while (!state.getBoolean("done")) {
                check(!closed && !Thread.currentThread().isInterrupted) { "Servidor encerrado" }
                check(resolve(path)?.canonicalPath == canonical && file.length() == size && file.lastModified() == modified) { "Arquivo de origem mudou durante o envio" }
                val offset = state.getLong("offset")
                check(offset in 0..size) { "Destino retornou progresso inválido" }
                val length = minOf(TransferStore.CHUNK.toLong(), size - offset)
                check(length > 0) { "Destino retornou progresso incompleto inválido" }
                val c = connection(base + "/api/transfer/chunk?id=${state.getString("id")}&offset=$offset", secret)
                try {
                    c.requestMethod = "PUT"; c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/octet-stream"); c.setFixedLengthStreamingMode(length)
                    input.seek(offset)
                    c.outputStream.use { out ->
                        val buffer = ByteArray(256 * 1024); var remaining = length
                        while (remaining > 0) {
                            check(!closed && !Thread.currentThread().isInterrupted) { "Envio encerrado" }
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            check(n > 0) { "Arquivo de origem mudou" }
                            out.write(buffer, 0, n); remaining -= n
                        }
                    }
                    state = response(c); attempts = 0
                } catch (e: Exception) {
                    if (e is RemoteFailure && e.code < 500 && e.code != 409) throw e
                    if (++attempts >= 3 || closed) throw e
                    Thread.sleep(300L * attempts)
                    state = json(base, secret, "/api/transfer/status?id=${state.getString("id")}")
                } finally { disconnect(c) }
            }
        }
        check(file.length() == size && file.lastModified() == modified) { "A origem mudou; confirme o arquivo recebido no destino" }
        return state.getString("name")
    }
    private fun json(base: String, secret: String, path: String, body: String? = null): JSONObject {
        val c = connection(base + path, secret)
        try {
            if (body != null) {
                val bytes = body.toByteArray()
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.setFixedLengthStreamingMode(bytes.size); c.outputStream.use { it.write(bytes) }
            }
            return response(c)
        } finally { disconnect(c) }
    }
    private fun connection(url: String, secret: String): HttpURLConnection {
        check(!closed) { "Servidor encerrado" }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 120000; instanceFollowRedirects = false; setRequestProperty("X-Key", secret)
        }
        connections.add(c)
        return c
    }
    private fun disconnect(c: HttpURLConnection) { connections.remove(c); c.disconnect() }
    private fun response(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val bytes = stream?.use { input ->
            val data = input.readBytesBounded(262144)
            data.toString(Charsets.UTF_8)
        }.orEmpty()
        val result = try { JSONObject(bytes) } catch (_: Exception) { throw IllegalStateException("Destino retornou uma resposta inválida") }
        if (code !in 200..299) throw RemoteFailure(code, result.optString("error", "Erro de comunicação $code"))
        return result
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) { val n = read(buffer); if (n < 0) break; check(out.size() + n <= limit) { "Resposta do destino muito grande" }; out.write(buffer, 0, n) }
        return out.toByteArray()
    }
    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")
    private fun validAddress(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val numbers = parts.map { it.toIntOrNull() ?: return false }
        if (numbers.any { it !in 0..255 }) return false
        // Discovery uses IPv4. Only LAN, loopback (tests/local peer), or Tailscale peers are eligible.
        return numbers[0] == 10 || numbers[0] == 127 || numbers[0] == 192 && numbers[1] == 168 ||
            numbers[0] == 172 && numbers[1] in 16..31 || numbers[0] == 100 && numbers[1] in 64..127 || numbers[0] == 169 && numbers[1] == 254
    }
    override fun close() { closed = true; connections.toList().forEach { disconnect(it) }; executor.shutdownNow() }
}
