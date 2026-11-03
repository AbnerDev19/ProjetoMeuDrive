package com.meudrive

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

class InboxActivity : Activity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 44), Ui.dp(ctx, 16), Ui.dp(ctx, 24))
        }
        root.addView(Ui.text(ctx, "Caixa de entrada", 26f, Ui.TEXT, true))
        root.addView(Ui.text(ctx, "Textos, links e arquivos que outros aparelhos mandaram para cá.", 14f, Ui.MUTED))
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, Ui.lp(ctx, 16, -1))
        setContentView(ScrollView(ctx).apply { setBackgroundColor(Ui.SIDE); addView(root) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        val items = Inbox.all(this)
        if (items.isEmpty()) {
            list.addView(Ui.text(this, "Nada por aqui ainda.", 14f, Ui.MUTED))
            return
        }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        items.forEach { list.addView(card(it, fmt)) }
    }

    private fun isLink(t: String): Boolean {
        val s = t.trim()
        return (s.startsWith("http://") || s.startsWith("https://")) && !s.contains(' ') && !s.contains('\n')
    }

    private fun card(item: InboxItem, fmt: DateFormat): LinearLayout {
        val c = Ui.card(this)
        val who = item.from.ifBlank { "outro aparelho" }
        val head = (if (item.kind == "file") "Arquivo" else "Texto") + " de " + who + " · " + fmt.format(Date(item.time))
        c.addView(Ui.text(this, head, 12f, Ui.MUTED))
        val body = Ui.text(this, item.text, 15f, Ui.TEXT)
        body.setTextIsSelectable(true)
        c.addView(body)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (item.kind == "file") {
            c.addView(Ui.text(this, "Salvo em Meu Drive/" + item.path, 12f, Ui.MUTED))
        } else {
            row.addView(Ui.secondary(this, "Copiar") { copy(item.text) }, LinearLayout.LayoutParams(0, -2, 1f))
            if (isLink(item.text)) {
                row.addView(Ui.secondary(this, "Abrir") { open(item.text.trim()) }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        row.addView(Ui.secondary(this, "Apagar") {
            Inbox.remove(this, item.id)
            render()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(row, Ui.lp(this, 8, -1))
        return c
    }

    private fun copy(t: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Meu Drive", t))
        Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Nenhum app abre esse link", Toast.LENGTH_SHORT).show()
        }
    }
}
