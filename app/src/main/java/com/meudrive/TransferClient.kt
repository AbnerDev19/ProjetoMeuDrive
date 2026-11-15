package com.meudrive

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Same chunk protocol used by the browser. Re-selecting the same document resumes within 24h. */
class TransferClient(private val ctx: Context) {
    fun send(base: String, key: String, uri: Uri, name: String, declaredSize: Long, progress: (Long, Long) -> Unit) {
        var temporary: File? = null
        try {
            val size = if (declaredSize >= 0) declaredSize else {
                temporary = File.createTempFile("unknown-size", ".tmp", ctx.cacheDir)
                ctx.contentResolver.openInputStream(uri)!!.use { input -> temporary!!.outputStream().use { input.copyTo(it, 256 * 1024) } }
                temporary!!.length()
            }
            val prefs = ctx.getSharedPreferences("transfers", 0)
            val identity = Cfg.hash("$base|$uri|$name|$size")
            var state = prefs.getString(identity, null)?.let { id ->
                try { json(base, key, "/api/transfer/status?id=$id") } catch (_: Exception) { null }
            }
            if (state == null) state = json(base, key, "/api/transfer/start", "path=Transferencias&name=${enc(name)}&size=$size&from=${enc(Cfg.deviceName(ctx))}")
            prefs.edit().putString(identity, state.getString("id")).apply()
            var current: JSONObject = state
            var failures = 0
            while (!current.getBoolean("done")) {
                val offset = current.getLong("offset")
                val length = minOf(TransferStore.CHUNK.toLong(), size - offset)
                val conn = connection(base + "/api/transfer/chunk?id=${current.getString("id")}&offset=$offset", key).apply {
                    requestMethod = "PUT"; doOutput = true; setFixedLengthStreamingMode(length)
                    setRequestProperty("Content-Type", "application/octet-stream")
                }
                try {
                    val input = temporary?.inputStream() ?: ctx.contentResolver.openInputStream(uri)!!
                    input.use { src ->
                        var skip = offset
                        while (skip > 0) {
                            val n = src.skip(skip)
                            if (n > 0) skip -= n else { check(src.read() >= 0) { "Arquivo de origem mudou" }; skip-- }
                        }
                        conn.outputStream.use { out ->
                            var remaining = length
                            val buffer = ByteArray(256 * 1024)
                            while (remaining > 0) {
                                val n = src.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                check(n > 0) { "Arquivo de origem mudou" }
                                out.write(buffer, 0, n); remaining -= n; progress(offset + length - remaining, size)
                            }
                        }
                    }
                    current = response(conn)
                    failures = 0
                } catch (e: Exception) {
                    if (++failures >= 4) throw e
                    Thread.sleep(500L * failures)
                    current = json(base, key, "/api/transfer/status?id=${current.getString("id")}")
                } finally { conn.disconnect() }
            }
            prefs.edit().remove(identity).apply()
            progress(size, size)
        } finally { temporary?.delete() }
    }
    private fun json(base: String, key: String, path: String, body: String? = null): JSONObject {
        val c = connection(base + path, key)
        try {
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.setFixedLengthStreamingMode(bytes.size); c.outputStream.use { it.write(bytes) }
            }
            return response(c)
        } finally { c.disconnect() }
    }
    private fun connection(url: String, key: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5000; readTimeout = 120000; instanceFollowRedirects = false; setRequestProperty("X-Key", key)
    }
    private fun response(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        val data = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
        check(code in 200..299) { data.optString("error", "Erro HTTP $code") }
        return data
    }
    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")
}
