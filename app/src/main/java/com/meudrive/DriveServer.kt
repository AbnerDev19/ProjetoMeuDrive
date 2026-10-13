package com.meudrive

import android.content.Context
import android.os.Environment
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections

object Cfg {
    const val PORT = 8080

    fun hash(p: String): String =
        MessageDigest.getInstance("SHA-256").digest(p.toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun rootDir(ctx: Context): File {
        val base = if (Environment.isExternalStorageManager())
            Environment.getExternalStorageDirectory()
        else ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(base, "MeuDrive").also { it.mkdirs() }
    }
}

class DriveServer(private val ctx: Context) : NanoHTTPD(Cfg.PORT) {

    private val tokens: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val root: File get() = Cfg.rootDir(ctx)

    override fun serve(session: IHTTPSession): Response {
        return try {
            route(session)
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "erro"))
        }
    }

    private fun route(s: IHTTPSession): Response {
        val uri = s.uri
        if (uri == "/") {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "text/html; charset=utf-8", WEB_PAGE
            )
        }
        if (uri == "/api/login" && s.method == Method.POST) return login(s)
        if (!authed(s)) {
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "nao autorizado"))
        }

        val files = HashMap<String, String>()
        if (s.method == Method.POST || s.method == Method.PUT) s.parseBody(files)
        val rel = s.parms["path"] ?: ""

        return when (uri) {
            "/api/list" -> list(rel)
            "/api/download" -> download(rel)
            "/api/upload" -> upload(rel, s.parms["file"], files["file"])
            "/api/mkdir" -> mkdir(rel, s.parms["name"])
            "/api/delete" -> delete(rel)
            else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "nao encontrado"))
        }
    }

    // ---------- autenticação ----------

    private fun login(s: IHTTPSession): Response {
        val tmp = HashMap<String, String>()
        s.parseBody(tmp)
        Thread.sleep(800) // freia tentativas de força bruta
        val pw = s.parms["password"]
        val stored = ctx.getSharedPreferences("cfg", 0).getString("pwhash", null)
        val ok = stored != null && pw != null &&
            MessageDigest.isEqual(Cfg.hash(pw).toByteArray(), stored.toByteArray())
        if (!ok) return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "senha incorreta"))

        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        tokens.add(token)
        val r = json(Response.Status.OK, JSONObject().put("ok", true))
        r.addHeader("Set-Cookie", "sid=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=2592000")
        return r
    }

    private fun authed(s: IHTTPSession): Boolean {
        val c = s.headers["cookie"] ?: return false
        return c.split(";").map { it.trim() }
            .any { it.startsWith("sid=") && tokens.contains(it.substring(4)) }
    }

    // ---------- arquivos ----------

    private fun resolve(rel: String): File? {
        val base = root.canonicalFile
        val f = File(base, rel).canonicalFile
        return if (f == base || f.path.startsWith(base.path + File.separator)) f else null
    }

    private fun bad(msg: String = "caminho invalido") =
        json(Response.Status.BAD_REQUEST, JSONObject().put("error", msg))

    private fun list(rel: String): Response {
        val dir = resolve(rel) ?: return bad()
        if (!dir.isDirectory) return bad("nao e uma pasta")
        val arr = JSONArray()
        dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.forEach {
                arr.put(
                    JSONObject().put("name", it.name).put("dir", it.isDirectory)
                        .put("size", it.length()).put("mtime", it.lastModified())
                )
            }
        return json(Response.Status.OK, JSONObject().put("path", rel).put("items", arr))
    }

    private fun download(rel: String): Response {
        val f = resolve(rel) ?: return bad()
        if (!f.isFile) return json(Response.Status.NOT_FOUND, JSONObject().put("error", "arquivo nao encontrado"))
        val mime = URLConnection.guessContentTypeFromName(f.name) ?: "application/octet-stream"
        val r = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(f), f.length())
        val enc = URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
        r.addHeader("Content-Disposition", "attachment; filename*=UTF-8''$enc")
        return r
    }

    private fun upload(rel: String, name: String?, tmpPath: String?): Response {
        val dir = resolve(rel) ?: return bad()
        if (name == null || tmpPath == null) return bad("nenhum arquivo enviado")
        dir.mkdirs()
        val clean = File(name).name
        val base = File(clean).nameWithoutExtension
        val ext = File(clean).extension
        var dest = File(dir, clean)
        var n = 1
        while (dest.exists()) {
            dest = File(dir, base + " (" + n + ")" + (if (ext.isEmpty()) "" else ".$ext"))
            n++
        }
        val tmp = File(tmpPath)
        tmp.copyTo(dest)
        tmp.delete()
        return json(Response.Status.OK, JSONObject().put("ok", true).put("name", dest.name))
    }

    private fun mkdir(rel: String, name: String?): Response {
        val dir = resolve(rel) ?: return bad()
        val clean = File(name ?: "").name
        if (clean.isEmpty()) return bad("nome invalido")
        File(dir, clean).mkdirs()
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun delete(rel: String): Response {
        val f = resolve(rel) ?: return bad()
        if (f == root.canonicalFile) return bad("nao pode apagar a raiz")
        if (!f.exists()) return bad("nao existe")
        f.deleteRecursively()
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun json(status: Response.IStatus, o: JSONObject): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString())
}
