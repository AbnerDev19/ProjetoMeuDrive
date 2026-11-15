package com.meudrive

import android.content.Context
import android.os.Build
import android.os.Environment
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.net.URLConnection
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

object Cfg {
    const val PORT = 8080
    const val SERVICE_TYPE = "_meudrive._tcp."
    fun deviceName(ctx: Context): String = ctx.getSharedPreferences("cfg", 0)
        .getString("devname", null)?.takeIf { it.isNotBlank() } ?: Build.MODEL
    fun hash(p: String): String = MessageDigest.getInstance("SHA-256").digest(p.toByteArray())
        .joinToString("") { "%02x".format(it) }
    fun ips(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>().mapNotNull { it.hostAddress }
    } catch (_: Exception) { emptyList() }
    fun rootDir(ctx: Context): File {
        val permitted = Environment.isExternalStorageManager()
        val base = if (permitted) Environment.getExternalStorageDirectory() else ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val shared = ctx.getSharedPreferences("cfg", 0).getBoolean("shared_storage", false)
        return (if (shared && permitted) base else File(base, "MeuDrive")).also {
            it.mkdirs(); File(it, "Transferencias").mkdirs()
        }
    }
}

class DriveServer(private val ctx: Context, private val peers: () -> List<Peer> = { emptyList() }) : NanoHTTPD(Cfg.PORT) {
    init { setAsyncRunner(BoundedRunner()) }
    private data class Token(val expires: Long, val key: String)
    private val tokens = ConcurrentHashMap<String, Token>()
    private val attempts = ConcurrentHashMap<String, Pair<Long, Int>>()
    private val root: File get() = Cfg.rootDir(ctx)
    private val transfers = TransferStore(File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, ".transfers"), ::resolve)

    override fun serve(s: IHTTPSession): Response {
        val r = try { route(s) }
        catch (e: IllegalArgumentException) { error(Response.Status.BAD_REQUEST, e.message ?: "Dados inválidos") }
        catch (e: IllegalStateException) { error(Response.Status.CONFLICT, e.message ?: "Tente novamente") }
        catch (_: Exception) { error(Response.Status.INTERNAL_ERROR, "Falha ao acessar armazenamento ou conexão interrompida") }
        // Rejected bodies may remain unread; never reuse their HTTP connection.
        if (r.status.requestStatus >= 400) r.closeConnection(true)
        r.addHeader("X-Content-Type-Options", "nosniff")
        r.addHeader("Cache-Control", "no-store")
        r.addHeader("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; object-src 'none'; frame-ancestors 'none'")
        return r
    }

    private fun route(s: IHTTPSession): Response {
        if (s.uri == "/" && s.method == Method.GET)
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", ctx.assets.open("index.html").bufferedReader().use { it.readText() })
        if (s.uri == "/fonts/jakarta.ttf" && s.method == Method.GET)
            return newChunkedResponse(Response.Status.OK, "font/ttf", ctx.assets.open("fonts/jakarta.ttf"))
        // Reject cross-origin writes, including form posts to LAN IPs from unrelated sites.
        val origin = s.headers["origin"]
        if (origin != null && try { URI(origin).rawAuthority != s.headers["host"] } catch (_: Exception) { true })
            return error(Response.Status.FORBIDDEN, "Origem inválida")
        if (s.uri == "/api/login" && s.method == Method.POST) return login(s)
        if (!authed(s)) return error(Response.Status.UNAUTHORIZED, "Entre com a senha da casa")
        val read = setOf("/api/list", "/api/download", "/api/whoami", "/api/inbox", "/api/transfer/status", "/api/peers")
        val expected = if (s.uri == "/api/transfer/chunk") Method.PUT else if (s.uri in read) Method.GET else Method.POST
        if (s.method != expected) return error(Response.Status.METHOD_NOT_ALLOWED, "Método inválido")
        if (s.uri == "/api/transfer/chunk") {
            val length = s.headers["content-length"]?.toLongOrNull() ?: return bad("Content-Length obrigatório")
            return stateJson(transfers.append(s.parms["id"].orEmpty(), s.parms["offset"]?.toLongOrNull() ?: -1, length, s.inputStream))
        }
        if (s.method == Method.POST) {
            val length = s.headers["content-length"]?.toLongOrNull() ?: 0
            if (length !in 0..262144) return bad("Use envio em blocos para arquivos")
            s.parseBody(HashMap())
        }
        val rel = s.parms["path"].orEmpty()
        return when (s.uri) {
            "/api/peers" -> json(Response.Status.OK, JSONObject().put("items", JSONArray().apply {
                peers().forEach { put(JSONObject().put("name", it.name).put("host", it.host).put("port", it.port)) }
            }))
            "/api/list" -> list(rel)
            "/api/download" -> download(rel, s)
            "/api/transfer/start" -> stateJson(transfers.start(rel, s.parms["name"].orEmpty(), s.parms["size"]?.toLongOrNull() ?: -1, s.parms["from"] ?: "Navegador"))
            "/api/transfer/status" -> stateJson(transfers.state(s.parms["id"].orEmpty()))
            "/api/mkdir" -> {
                val name = s.parms["name"].orEmpty()
                require(TransferStore.validName(name)) { "Nome inválido" }
                val dir = resolve(rel) ?: return bad()
                check(dir.isDirectory && File(dir, name).mkdir()) { "Pasta já existe ou não pode ser criada" }; ok()
            }
            "/api/delete" -> {
                val f = resolve(rel) ?: return bad()
                require(f != root.canonicalFile) { "Não pode apagar a raiz" }
                check(f.exists() && f.deleteRecursively()) { "Não foi possível apagar" }; ok()
            }
            "/api/whoami" -> json(Response.Status.OK, JSONObject().put("name", Cfg.deviceName(ctx))
                .put("total", root.totalSpace).put("free", root.usableSpace).put("root", root.path)
                .put("shared", ctx.getSharedPreferences("cfg", 0).getBoolean("shared_storage", false)))
            "/api/send-text" -> {
                val text = s.parms["text"]?.trim().orEmpty()
                require(text.isNotEmpty() && text.length <= 100000) { "Texto vazio ou grande demais" }
                val from = s.parms["from"]?.take(120) ?: "Navegador"
                Inbox.add(ctx, "text", text, from); runCatching { Notifier.received(ctx, "De $from", text.take(300)) }; ok()
            }
            "/api/inbox" -> json(Response.Status.OK, JSONObject().put("items", JSONArray().apply { Inbox.all(ctx).forEach { put(Inbox.toJson(it)) } }))
            "/api/inbox/delete" -> { Inbox.remove(ctx, s.parms["id"]?.toLongOrNull() ?: return bad("ID inválido")); ok() }
            "/api/logout" -> {
                s.headers["cookie"].orEmpty().split(';').map { it.trim() }.filter { it.startsWith("sid=") }
                    .forEach { tokens.remove(it.removePrefix("sid=")) }
                ok().apply { addHeader("Set-Cookie", "sid=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0") } }
            else -> error(Response.Status.NOT_FOUND, "Não encontrado")
        }
    }

    private fun login(s: IHTTPSession): Response {
        val now = System.currentTimeMillis()
        val host = s.remoteIpAddress
        val old = attempts[host]
        if (old != null && now - old.first < 60000 && old.second >= 8)
            return error(Response.Status.TOO_MANY_REQUESTS, "Aguarde um minuto antes de tentar novamente")
        if ((s.headers["content-length"]?.toLongOrNull() ?: 0) !in 1..4096) return bad("Senha inválida")
        s.parseBody(HashMap())
        val stored = ctx.getSharedPreferences("cfg", 0).getString("pwhash", null)
        val pw = s.parms["password"]
        if (stored == null || pw == null || !MessageDigest.isEqual(Cfg.hash(pw).toByteArray(), stored.toByteArray())) {
            if (attempts.size > 256) attempts.clear()
            attempts[host] = if (old == null || now - old.first >= 60000) now to 1 else old.first to old.second + 1
            return error(Response.Status.UNAUTHORIZED, "Senha incorreta")
        }
        attempts.remove(host)
        tokens.entries.removeAll { it.value.expires < now }
        if (tokens.size >= 128) tokens.clear()
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        tokens[token] = Token(now + 86400000, stored)
        return ok().apply { addHeader("Set-Cookie", "sid=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=86400") }
    }
    private fun authed(s: IHTTPSession): Boolean {
        val stored = ctx.getSharedPreferences("cfg", 0).getString("pwhash", null) ?: return false
        s.headers["x-key"]?.let { if (MessageDigest.isEqual(it.toByteArray(), stored.toByteArray())) return true }
        return s.headers["cookie"].orEmpty().split(';').any {
            val token = tokens[it.trim().removePrefix("sid=")]
            it.trim().startsWith("sid=") && token != null && token.expires > System.currentTimeMillis() && token.key == stored
        }
    }
    private fun resolve(rel: String): File? {
        val base = root.canonicalFile
        val file = File(base, rel).canonicalFile
        if (file != base && !file.path.startsWith(base.path + File.separator)) return null
        if (file.path.split(File.separatorChar).any { it == "Android" || it == ".transfers" }) return null
        return file
    }
    private fun list(rel: String): Response {
        val dir = resolve(rel) ?: return bad()
        require(dir.isDirectory) { "Não é uma pasta" }
        val arr = JSONArray()
        dir.listFiles()?.filter { resolve(if (rel.isEmpty()) it.name else "$rel/${it.name}") != null }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.forEach {
                arr.put(JSONObject().put("name", it.name).put("dir", it.isDirectory).put("size", if (it.isDirectory) 0 else it.length()).put("mtime", it.lastModified()))
            }
        return json(Response.Status.OK, JSONObject().put("path", rel).put("items", arr))
    }
    private fun download(rel: String, s: IHTTPSession): Response {
        val f = resolve(rel) ?: return bad()
        if (!f.isFile) return error(Response.Status.NOT_FOUND, "Arquivo não encontrado")
        val size = f.length()
        val etag = "\"${size.toString(16)}-${f.lastModified().toString(16)}\""
        val rangeHeader = s.headers["range"]?.takeIf { s.headers["if-range"] == null || s.headers["if-range"] == etag }
        val range = rangeHeader?.let { ByteRange.parse(it, size) }
        if (rangeHeader != null && range == null) return error(Response.Status.RANGE_NOT_SATISFIABLE, "Intervalo inválido").apply { addHeader("Content-Range", "bytes */$size") }
        val input = FileInputStream(f)
        try { if (range != null) input.channel.position(range.start) } catch (e: Exception) { input.close(); throw e }
        val count = if (range == null) size else range.end - range.start + 1
        return newFixedLengthResponse(if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
            URLConnection.guessContentTypeFromName(f.name) ?: "application/octet-stream", input, count).apply {
            addHeader("Accept-Ranges", "bytes"); addHeader("ETag", etag)
            if (range != null) addHeader("Content-Range", "bytes ${range.start}-${range.end}/$size")
            addHeader("Content-Disposition", "attachment; filename*=UTF-8''${URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")}")
        }
    }
    private fun stateJson(t: TransferStore.State): Response {
        if (t.done) transfers.takeReceipt(t.id)?.let { receipt ->
            Inbox.add(ctx, "file", receipt.name, receipt.from, receipt.path)
            runCatching { Notifier.received(ctx, "Arquivo de ${receipt.from}", receipt.name) }
        }
        return json(Response.Status.OK, JSONObject().put("id", t.id).put("offset", t.offset)
            .put("size", t.size).put("name", t.name).put("done", t.done).put("chunkSize", TransferStore.CHUNK))
    }
    private fun bad(msg: String = "Caminho inválido") = error(Response.Status.BAD_REQUEST, msg)
    private fun error(status: Response.IStatus, msg: String) = json(status, JSONObject().put("error", msg))
    private fun ok() = json(Response.Status.OK, JSONObject().put("ok", true))
    private fun json(status: Response.IStatus, value: JSONObject) = newFixedLengthResponse(status, "application/json; charset=utf-8", value.toString())
}
