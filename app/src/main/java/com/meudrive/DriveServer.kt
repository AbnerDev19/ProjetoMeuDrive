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
    private val registry = PeerRegistry()
    private val operations = PeerOperations(::resolve, peers,
        { ctx.getSharedPreferences("cfg", 0).getString("pwhash", null) }, { Cfg.deviceName(ctx) })
    override fun stop() { operations.close(); super.stop() }
    private val previews = ImagePreview(ctx)
    private val root: File get() = Cfg.rootDir(ctx)
    private val media = MediaIndex({ root }, ::resolve)
    private val transfers = TransferStore(File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, ".transfers"), ::resolve)

    override fun serve(s: IHTTPSession): Response {
        val r = try { route(s) }
        catch (e: org.json.JSONException) { error(Response.Status.BAD_REQUEST, "Seleção inválida") }
        catch (e: IllegalArgumentException) { error(Response.Status.BAD_REQUEST, e.message ?: "Dados inválidos") }
        catch (e: IllegalStateException) { error(Response.Status.CONFLICT, e.message ?: "Tente novamente") }
        catch (_: Exception) { error(Response.Status.INTERNAL_ERROR, "Falha ao acessar armazenamento ou conexão interrompida") }
        // Rejected bodies may remain unread; never reuse their HTTP connection.
        if (r.status.requestStatus >= 400) r.closeConnection(true)
        r.addHeader("X-Content-Type-Options", "nosniff")
        r.addHeader("Cache-Control", if (s.uri.startsWith("/pdfjs/") && r.status.requestStatus == 200) "public, max-age=86400" else if (s.uri == "/api/preview" && r.status.requestStatus in setOf(200, 304)) "private, max-age=60" else "no-store")
        r.addHeader("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; worker-src 'self' blob:; frame-src 'self'; media-src 'self' blob:; style-src 'self' 'unsafe-inline'; img-src 'self' data:; object-src 'none'; frame-ancestors ${if (s.uri == "/pdf-viewer.html") "'self'" else "'none'"}")
        return r
    }

    private fun route(s: IHTTPSession): Response {
        if (s.method == Method.GET) ViewerAssets.serve(ctx, s.uri)?.let { return it }
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
        val read = setOf("/api/list", "/api/media", "/api/open", "/api/download", "/api/preview", "/api/whoami", "/api/inbox", "/api/transfer/status", "/api/peers", "/api/peer-send/status")
        val expected = if (s.uri == "/api/transfer/chunk") Method.PUT else if (s.uri in read) Method.GET else Method.POST
        if (s.method != expected && !(s.method == Method.HEAD && s.uri in setOf("/api/download", "/api/open"))) return error(Response.Status.METHOD_NOT_ALLOWED, "Método inválido")
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
            "/api/peers" -> json(Response.Status.OK, JSONObject().put("items", registry.list(peers())))
            "/api/heartbeat" -> json(Response.Status.OK, registry.heartbeat(s.parms["deviceId"].orEmpty(), s.parms["name"].orEmpty()))
            "/api/delete-batch" -> json(Response.Status.OK, operations.deleteBatch(operations.selected(s.parms["paths"].orEmpty())))
            "/api/peer-send" -> json(Response.Status.OK, operations.send(s.parms["target"].orEmpty(), operations.selected(s.parms["paths"].orEmpty())))
            "/api/peer-send/status" -> json(Response.Status.OK, operations.status(s.parms["id"].orEmpty()))
            "/api/list" -> list(rel)
            "/api/media" -> media(s)
            "/api/open" -> download(rel, s, true)
            "/api/download" -> download(rel, s)
            "/api/preview" -> preview(rel, s)
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
        return ok().apply { addHeader("Set-Cookie", "sid=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=86400" + if (s.headers["x-forwarded-proto"] == "https") "; Secure" else "") }
    }
    private fun authed(s: IHTTPSession): Boolean {
        val stored = ctx.getSharedPreferences("cfg", 0).getString("pwhash", null) ?: return false
        s.headers["x-key"]?.let { if (MessageDigest.isEqual(it.toByteArray(), stored.toByteArray())) return true }
        return s.headers["cookie"].orEmpty().split(';').any {
            val token = tokens[it.trim().removePrefix("sid=")]
            it.trim().startsWith("sid=") && token != null && token.expires > System.currentTimeMillis() && token.key == stored
        }
    }
    private fun resolve(rel: String): File? = StorageBoundary.resolve(root, rel)
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
    private fun download(rel: String, s: IHTTPSession, inline: Boolean = false): Response {
        val f = resolve(rel) ?: return bad()
        if (!f.isFile) return error(Response.Status.NOT_FOUND, "Arquivo não encontrado")
        val mime = if (inline) MediaIndex.mime(f) ?: return bad("Formato sem visualizador seguro; baixe o original")
            else URLConnection.guessContentTypeFromName(f.name) ?: "application/octet-stream"
        val size = f.length()
        val etag = "\"${size.toString(16)}-${f.lastModified().toString(16)}\""
        val rangeHeader = s.headers["range"]?.takeIf { s.headers["if-range"] == null || s.headers["if-range"] == etag }
        val range = rangeHeader?.let { ByteRange.parse(it, size) }
        if (rangeHeader != null && range == null) return error(Response.Status.RANGE_NOT_SATISFIABLE, "Intervalo inválido").apply { addHeader("Content-Range", "bytes */$size") }
        val input = FileInputStream(f)
        try { if (range != null) input.channel.position(range.start) } catch (e: Exception) { input.close(); throw e }
        val count = if (range == null) size else range.end - range.start + 1
        return newFixedLengthResponse(if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
            mime, input, count).apply {
            addHeader("Accept-Ranges", "bytes"); addHeader("ETag", etag)
            if (range != null) addHeader("Content-Range", "bytes ${range.start}-${range.end}/$size")
            addHeader("Content-Disposition", "${if (inline) "inline" else "attachment"}; filename*=UTF-8''${URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")}")
        }
    }
    private fun media(s: IHTTPSession): Response {
        val page = media.page(s.parms["kind"] ?: "all", s.parms["offset"]?.toIntOrNull() ?: 0,
            s.parms["limit"]?.toIntOrNull() ?: 60, s.parms["refresh"] == "1")
        val items = JSONArray()
        page.items.forEach { items.put(JSONObject().put("name", it.name).put("path", it.path).put("kind", it.kind)
            .put("size", it.size).put("mtime", it.mtime).put("dir", false)) }
        return json(Response.Status.OK, JSONObject().put("items", items).put("total", page.total)
            .put("nextOffset", page.nextOffset ?: JSONObject.NULL).put("partial", page.partial).put("scannedAt", page.scannedAt))
    }

    private fun preview(rel: String, s: IHTTPSession): Response {
        val file = resolve(rel) ?: return bad()
        val result = previews.make(file, s.parms["size"]?.toIntOrNull(), s.parms["full"] == "1")
        if (s.headers["if-none-match"] == result.etag)
            return newFixedLengthResponse(Response.Status.NOT_MODIFIED, "image/jpeg", "").apply { addHeader("ETag", result.etag) }
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", result.bytes.inputStream(), result.bytes.size.toLong()).apply {
            addHeader("ETag", result.etag)
            addHeader("Content-Disposition", "inline")
            addHeader("Vary", "Cookie, X-Key")
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
