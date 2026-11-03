package com.meudrive

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Tela de envio. Recebe texto, link ou arquivos (pelo Compartilhar do Android ou pelo
 * próprio app) e deixa escolher o destino: este celular ou outro aparelho achado na rede.
 */
class ShareActivity : Activity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvInfo: TextView
    private lateinit var bar: ProgressBar
    private lateinit var action: Button
    private lateinit var targets: LinearLayout

    private var discovery: Discovery? = null
    private var uris: List<Uri> = emptyList()
    private var text: String? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 56), Ui.dp(ctx, 16), Ui.dp(ctx, 24))
        }
        val card = Ui.card(ctx)
        tvTitle = Ui.text(ctx, "Enviar para…", 20f, Ui.TEXT, true)
        tvInfo = Ui.text(ctx, "", 14f, Ui.MUTED)
        targets = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        action = Ui.primary(ctx, "Fechar") { finish() }.apply { visibility = View.GONE }
        card.addView(tvTitle)
        card.addView(tvInfo)
        card.addView(targets, Ui.lp(ctx, 8, -1))
        card.addView(bar, Ui.lp(ctx, 12, -1))
        card.addView(action, Ui.lp(ctx, 16))
        root.addView(card)
        setContentView(ScrollView(ctx).apply { setBackgroundColor(Ui.SIDE); addView(root) })

        uris = extractUris(intent)
        text = if (uris.isEmpty()) extractText(intent) else null

        if (uris.isEmpty() && text.isNullOrBlank()) {
            tvTitle.text = "Nada para enviar"
            tvInfo.text = "Nenhum arquivo ou texto foi recebido."
            action.visibility = View.VISIBLE
            return
        }

        tvInfo.text = if (uris.isNotEmpty()) uris.size.toString() + " arquivo(s) para enviar"
        else "Texto: " + text.orEmpty().take(120)

        discovery = Discovery(this).also {
            it.onChange = { renderTargets() }
            it.start()
        }
        renderTargets()
    }

    override fun onDestroy() {
        discovery?.stop()
        discovery = null
        super.onDestroy()
    }

    // ---------- destinos ----------

    private fun renderTargets() {
        if (busy) return
        targets.removeAllViews()
        targets.addView(
            Ui.primary(this, "Este celular (para pegar no computador)") { sendLocal() },
            Ui.lp(this, 6, -1)
        )
        val ps = discovery?.peers().orEmpty()
        ps.forEach { p ->
            targets.addView(Ui.secondary(this, p.name) { sendPeer(p) }, Ui.lp(this, 8, -1))
        }
        if (ps.isEmpty()) {
            targets.addView(
                Ui.text(
                    this,
                    "Procurando outros aparelhos na rede… Eles precisam estar com o servidor ligado, no mesmo Wi-Fi.",
                    13f, Ui.MUTED
                )
            )
        }
    }

    private fun startBusy(msg: String) {
        busy = true
        targets.visibility = View.GONE
        bar.progress = 0
        bar.visibility = View.VISIBLE
        tvTitle.text = msg
    }

    // ---------- leitura do que foi compartilhado ----------

    @Suppress("DEPRECATION")
    private fun extractUris(i: Intent): List<Uri> {
        val out = mutableListOf<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { out += it }
            Intent.ACTION_SEND_MULTIPLE ->
                i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out += it }
        }
        return out
    }

    private fun extractText(i: Intent): String? =
        if (i.action == Intent.ACTION_SEND) i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() else null

    private fun meta(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "arquivo"
        var size = -1L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        size = c.getLong(1)
                    }
                }
        } catch (_: Exception) {}
        return Pair(File(name).name.ifEmpty { "arquivo" }, size)
    }

    private fun unique(dir: File, name: String): File {
        val base = File(name).nameWithoutExtension
        val ext = File(name).extension
        var dest = File(dir, name)
        var n = 1
        while (dest.exists()) {
            dest = File(dir, base + " (" + n + ")" + (if (ext.isEmpty()) "" else ".$ext"))
            n++
        }
        return dest
    }

    // ---------- enviar para este celular ----------

    private fun sendLocal() {
        startBusy("Guardando neste celular")
        thread {
            if (uris.isEmpty()) {
                Inbox.add(this, "text", text.orEmpty(), "Este celular")
                runOnUiThread { showResult(1, 0, null, true, null) }
                return@thread
            }
            val dir = File(Cfg.rootDir(this), "Transferencias").apply { mkdirs() }
            var ok = 0
            var fail = 0
            uris.forEachIndexed { idx, uri ->
                val (name, size) = meta(uri)
                runOnUiThread {
                    tvInfo.text = "Copiando " + (idx + 1) + " de " + uris.size + ": " + name
                    bar.progress = 0
                }
                try {
                    val dest = unique(dir, name)
                    contentResolver.openInputStream(uri)!!.use { input ->
                        FileOutputStream(dest).use { out ->
                            copyStream(input, out, size)
                        }
                    }
                    Inbox.add(this, "file", dest.name, "Este celular", "Transferencias/" + dest.name)
                    ok++
                } catch (e: Exception) {
                    fail++
                }
            }
            runOnUiThread { showResult(ok, fail, null, true, null) }
        }
    }

    private fun copyStream(input: java.io.InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(256 * 1024)
        var total = 0L
        var n = 0
        var last = -1
        while (input.read(buf).also { n = it } >= 0) {
            out.write(buf, 0, n)
            total += n
            if (size > 0) {
                val pct = (total * 100 / size).toInt()
                if (pct != last) {
                    last = pct
                    runOnUiThread { bar.progress = pct }
                }
            }
        }
    }

    // ---------- enviar para outro aparelho ----------

    private fun sendPeer(p: Peer) {
        val key = getSharedPreferences("cfg", 0).getString("pwhash", null)
        if (key == null) {
            Toast.makeText(this, "Defina a senha na tela Servidor (a mesma em todos os aparelhos)", Toast.LENGTH_LONG).show()
            return
        }
        startBusy("Enviando para " + p.name)
        thread {
            var ok = 0
            var fail = 0
            var err: String? = null
            try {
                if (uris.isEmpty()) {
                    val code = postText(p, key, text.orEmpty())
                    if (code == 200) ok++ else { fail++; err = explain(code) }
                } else {
                    uris.forEachIndexed { idx, uri ->
                        val (name, size) = meta(uri)
                        runOnUiThread {
                            tvInfo.text = "Enviando " + (idx + 1) + " de " + uris.size + ": " + name
                            bar.progress = 0
                        }
                        try {
                            val code = postFile(p, key, uri, name, size)
                            if (code == 200) ok++ else { fail++; err = explain(code) }
                        } catch (e: Exception) {
                            fail++
                            err = "Não consegui falar com " + p.name
                        }
                    }
                }
            } catch (e: Exception) {
                fail++
                err = "Não consegui falar com " + p.name
            }
            runOnUiThread { showResult(ok, fail, err, false, p.name) }
        }
    }

    private fun explain(code: Int): String =
        if (code == 401) "A senha é diferente nesse aparelho. Use a mesma senha em todos."
        else "O outro aparelho respondeu com erro $code."

    private fun base(p: Peer) = "http://" + p.host + ":" + p.port

    private fun postText(p: Peer, key: String, t: String): Int {
        val body = ("text=" + URLEncoder.encode(t, "UTF-8") +
            "&from=" + URLEncoder.encode(Cfg.deviceName(this), "UTF-8")).toByteArray()
        val c = URL(base(p) + "/api/send-text").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5000
            c.readTimeout = 15000
            c.setRequestProperty("X-Key", key)
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            return c.responseCode
        } finally {
            c.disconnect()
        }
    }

    private fun postFile(p: Peer, key: String, uri: Uri, name: String, size0: Long): Int {
        // o servidor precisa do Content-Length; se o tamanho é desconhecido, copia pro cache antes
        var size = size0
        var tmpFile: File? = null
        if (size <= 0) {
            val t = File.createTempFile("send", ".tmp", cacheDir)
            contentResolver.openInputStream(uri)!!.use { i -> FileOutputStream(t).use { o -> i.copyTo(o) } }
            tmpFile = t
            size = t.length()
        }
        val srcFile = tmpFile
        try {
            val boundary = "----MeuDrive" + System.currentTimeMillis()
            val safe = name.replace("\"", "_").replace("\r", "").replace("\n", "")
            val head = ("--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"" + safe + "\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.UTF_8)
            val tail = ("\r\n--" + boundary + "--\r\n").toByteArray()
            val url = base(p) + "/api/upload?path=Transferencias&from=" +
                URLEncoder.encode(Cfg.deviceName(this), "UTF-8")

            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 5000
                c.readTimeout = 10 * 60 * 1000
                c.setRequestProperty("X-Key", key)
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary + "; charset=UTF-8")
                c.setFixedLengthStreamingMode(head.size.toLong() + size + tail.size.toLong())
                c.outputStream.use { out ->
                    out.write(head)
                    val input = if (srcFile != null) FileInputStream(srcFile) else contentResolver.openInputStream(uri)!!
                    input.use { copyStream(it, out, size) }
                    out.write(tail)
                }
                return c.responseCode
            } finally {
                c.disconnect()
            }
        } finally {
            srcFile?.delete()
        }
    }

    // ---------- resultado ----------

    private fun showResult(ok: Int, fail: Int, error: String?, local: Boolean, peer: String?) {
        busy = false
        bar.progress = 100
        tvTitle.text = if (fail == 0) "Pronto!" else "Concluído com falhas"
        val sb = StringBuilder()
        if (local) {
            if (uris.isEmpty()) sb.append("Texto salvo na caixa de entrada deste celular.")
            else sb.append(ok).append(" arquivo(s) na pasta Transferências.")
            if (fail > 0) sb.append(" (").append(fail).append(" falharam)")
            sb.append("\n\n")
            val p = getSharedPreferences("cfg", 0)
            val on = p.getBoolean("on", false)
            val hasPw = p.getString("pwhash", null) != null
            if (on) {
                sb.append("No computador, abra um destes endereços:\n")
                Cfg.ips().forEach { sb.append("http://").append(it).append(":").append(Cfg.PORT).append("\n") }
            } else if (hasPw) {
                sb.append("O servidor está desligado. Ligue-o para pegar no computador.")
            } else {
                sb.append("Falta definir a senha e ligar o servidor (tela Servidor).")
            }
            if (!on && hasPw) {
                action.text = "Ligar servidor agora"
                action.setOnClickListener {
                    startForegroundService(Intent(this, ServerService::class.java))
                    finish()
                }
            }
        } else {
            if (ok > 0) sb.append("Enviado para ").append(peer).append(".")
            if (fail > 0) {
                if (sb.isNotEmpty()) sb.append(" ")
                sb.append(fail).append(" falhou. ").append(error.orEmpty())
            }
        }
        tvInfo.text = sb.toString()
        action.visibility = View.VISIBLE
    }
}
