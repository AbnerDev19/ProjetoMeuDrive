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
    private data class Target(val name: String, val address: ServerAddress)

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
        val discovered = discovery?.peers().orEmpty().mapNotNull { p ->
            runCatching { Target(p.name, ServerAddress.parse("http://${p.host}:${p.port}")) }.getOrNull()
        }
        val prefs = getSharedPreferences("cfg", 0)
        val saved = listOf("lan_url" to "Servidor pelo Wi-Fi", "remote_url" to "Servidor fora de casa", "client_url" to "Último servidor")
            .mapNotNull { (key, label) -> prefs.getString(key, null)?.let { value ->
                runCatching { Target(label, ServerAddress.parse(value)) }.getOrNull()
            } }
        val ps = (saved + discovered).distinctBy { it.address.origin }
        ps.forEach { p ->
            targets.addView(Ui.secondary(this, "${p.name}\n${p.address.origin}") {
                android.app.AlertDialog.Builder(this).setTitle("Confirmar destino")
                    .setMessage("Enviar para este servidor?\n\n${p.address.origin}\n\nContinue somente se o endereço pertence a você. A chave da casa será enviada para ele.")
                    .setPositiveButton("Enviar") { _, _ -> sendPeer(p) }.setNegativeButton("Cancelar", null).show()
            }, Ui.lp(this, 8, -1))
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
        action.visibility = View.GONE
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
                        size = if (c.isNull(1)) -1 else c.getLong(1)
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
                    val staging = File(getExternalFilesDir(null) ?: filesDir, ".local-transfers").apply { mkdirs() }
                    staging.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > TransferStore.TTL }?.forEach { it.delete() }
                    val part = File.createTempFile("local-", ".part", staging)
                    try {
                    contentResolver.openInputStream(uri)!!.use { input ->
                        FileOutputStream(part).use { out ->
                            copyStream(input, out, size)
                        }
                    }
                    check(part.renameTo(dest)) { "Não foi possível guardar o arquivo" }
                    } finally { part.delete() }
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

    private fun sendPeer(p: Target) {
        val key = getSharedPreferences("cfg", 0).getString("pwhash", null)
        if (key == null) {
            Toast.makeText(this, "Defina a senha na tela Servidor (a mesma em todos os aparelhos)", Toast.LENGTH_LONG).show()
            return
        }
        startBusy("Enviando para " + p.name)
        val batch = uris.toList()
        thread {
            val failedUris = mutableListOf<Uri>()
            var ok = 0
            var fail = 0
            var err: String? = null
            try {
                if (uris.isEmpty()) {
                    val code = postText(p, key, text.orEmpty())
                    if (code == 200) ok++ else { fail++; err = explain(code) }
                } else {
                    batch.forEachIndexed { idx, uri ->
                        val (name, size) = meta(uri)
                        runOnUiThread {
                            tvInfo.text = "Enviando " + (idx + 1) + " de " + uris.size + ": " + name
                            bar.progress = 0
                        }
                        try {
                            val code = postFile(p, key, uri, name, size)
                            if (code == 200) ok++ else { fail++; failedUris.add(uri); err = explain(code) }
                        } catch (e: Exception) {
                            fail++; failedUris.add(uri)
                            err = e.message ?: "Não consegui falar com " + p.name
                        }
                    }
                }
            } catch (e: Exception) {
                fail++
                err = "Não consegui falar com " + p.name
            }
            runOnUiThread {
                showResult(ok, fail, err, false, p.name)
                if (fail > 0) {
                    action.text = "Tentar novamente (retomar envio)"
                    action.setOnClickListener { if (batch.isNotEmpty()) uris = failedUris.toList(); sendPeer(p) }
                }
            }
        }
    }

    private fun explain(code: Int): String =
        if (code == 401) "A senha é diferente nesse aparelho. Use a mesma senha em todos."
        else "O outro aparelho respondeu com erro $code."

    private fun base(p: Target) = p.address.origin

    private fun postText(p: Target, key: String, t: String): Int {
        val body = ("text=" + URLEncoder.encode(t, "UTF-8") +
            "&from=" + URLEncoder.encode(Cfg.deviceName(this), "UTF-8")).toByteArray()
        val c = URL(base(p) + "/api/send-text").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.instanceFollowRedirects = false
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

    private fun postFile(p: Target, key: String, uri: Uri, name: String, size: Long): Int {
        val started = System.nanoTime()
        var last = 0L
        TransferClient(this).send(base(p), key, uri, name, size) { sent, total ->
            val now = System.nanoTime()
            if (now - last > 150_000_000 || sent == total) {
                last = now
                val speed = sent / maxOf(0.1, (now - started) / 1e9) / 1048576
                runOnUiThread {
                    bar.progress = if (total > 0) (sent * 100 / total).toInt() else 100
                    tvInfo.text = "$name • ${sent / 1048576} / ${total / 1048576} MB • %.1f MB/s".format(speed)
                }
            }
        }
        return 200
    }

    // ---------- resultado ----------

    private fun showResult(ok: Int, fail: Int, error: String?, local: Boolean, peer: String?) {
        busy = false
        action.text = "Fechar"
        action.setOnClickListener { finish() }
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
