package com.meudrive

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class ShareActivity : Activity() {

    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var bar: ProgressBar
    private lateinit var action: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 56), Ui.dp(ctx, 16), Ui.dp(ctx, 24))
        }
        val card = Ui.card(ctx)
        title = Ui.text(ctx, "Enviando para o computador", 20f, Ui.TEXT, true)
        info = Ui.text(ctx, "Preparando...", 14f, Ui.MUTED)
        bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        action = Ui.primary(ctx, "Fechar") { finish() }.apply { visibility = android.view.View.GONE }
        card.addView(title)
        card.addView(info)
        card.addView(bar, Ui.lp(ctx, 12, -1))
        card.addView(action, Ui.lp(ctx, 16))
        root.addView(card)
        setContentView(ScrollView(ctx).apply { setBackgroundColor(Ui.SIDE); addView(root) })

        val uris = extract(intent)
        if (uris.isEmpty()) {
            title.text = "Nada para enviar"
            info.text = "Nenhum arquivo foi recebido."
            bar.visibility = android.view.View.GONE
            action.visibility = android.view.View.VISIBLE
            return
        }
        thread { copyAll(uris) }
    }

    @Suppress("DEPRECATION")
    private fun extract(i: Intent): List<Uri> {
        val out = mutableListOf<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { out += it }
            Intent.ACTION_SEND_MULTIPLE ->
                i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out += it }
        }
        return out
    }

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

    private fun copyAll(uris: List<Uri>) {
        val dir = File(Cfg.rootDir(this), "Transferencias").apply { mkdirs() }
        var ok = 0
        var fail = 0
        uris.forEachIndexed { idx, uri ->
            val (name, size) = meta(uri)
            runOnUiThread {
                info.text = "Copiando " + (idx + 1) + " de " + uris.size + ": " + name
                bar.progress = 0
            }
            try {
                val dest = unique(dir, name)
                contentResolver.openInputStream(uri)!!.use { input ->
                    FileOutputStream(dest).use { out ->
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
                }
                ok++
            } catch (e: Exception) {
                fail++
            }
        }
        runOnUiThread { finishUi(ok, fail) }
    }

    private fun finishUi(ok: Int, fail: Int) {
        val p = getSharedPreferences("cfg", 0)
        val on = p.getBoolean("on", false)
        val hasPw = p.getString("pwhash", null) != null
        bar.progress = 100
        title.text = if (fail == 0) "Pronto!" else "Concluído com falhas"
        val sb = StringBuilder()
        sb.append(ok).append(" arquivo(s) na pasta Transferências")
        if (fail > 0) sb.append(" (").append(fail).append(" falharam)")
        sb.append(".\n\n")
        if (on) {
            sb.append("No computador, abra um destes endereços e entre em Transferências:\n")
            Cfg.ips().forEach { sb.append("http://").append(it).append(":").append(Cfg.PORT).append("\n") }
        } else if (hasPw) {
            sb.append("O servidor está desligado. Ligue-o para baixar no computador.")
        } else {
            sb.append("Falta definir a senha e ligar o servidor (tela Servidor).")
        }
        info.text = sb.toString()
        action.visibility = android.view.View.VISIBLE
        if (!on && hasPw) {
            action.text = "Ligar servidor agora"
            action.setOnClickListener {
                startForegroundService(Intent(this, ServerService::class.java))
                finish()
            }
        }
    }
}
