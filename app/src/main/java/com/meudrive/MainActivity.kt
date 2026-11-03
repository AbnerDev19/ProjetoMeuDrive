package com.meudrive

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var msg: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 56), Ui.dp(ctx, 20), Ui.dp(ctx, 24))
        }
        root.addView(Ui.text(ctx, "Meu Drive", 30f, Ui.TEXT, true))
        root.addView(Ui.text(ctx, "Textos, links e arquivos entre os aparelhos da casa.", 15f, Ui.MUTED))

        val send = Ui.card(ctx).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(ctx, 24) }
        send.addView(Ui.text(ctx, "Enviar", 18f, Ui.TEXT, true))
        send.addView(Ui.text(ctx, "Escreva um texto ou link, ou escolha arquivos, e mande para outro aparelho.", 14f, Ui.MUTED))
        msg = EditText(ctx).apply {
            hint = "Texto ou link"
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(Ui.TEXT)
            background = Ui.shape(ctx, Ui.SIDE, 10, Ui.LINE)
            setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 12))
        }
        send.addView(msg, Ui.lp(ctx, 8, -1))
        send.addView(Ui.primary(ctx, "Enviar texto ou link") { sendText() }, Ui.lp(ctx))
        send.addView(Ui.secondary(ctx, "Escolher arquivos") { pickFiles() }, Ui.lp(ctx))
        root.addView(send)

        val inbox = Ui.card(ctx)
        inbox.addView(Ui.text(ctx, "Caixa de entrada", 18f, Ui.TEXT, true))
        inbox.addView(Ui.text(ctx, "O que outros aparelhos mandaram para este.", 14f, Ui.MUTED))
        inbox.addView(Ui.secondary(ctx, "Abrir caixa de entrada") {
            startActivity(Intent(ctx, InboxActivity::class.java))
        }, Ui.lp(ctx))
        root.addView(inbox)

        val server = Ui.card(ctx)
        server.addView(Ui.text(ctx, "Este aparelho", 18f, Ui.TEXT, true))
        server.addView(Ui.text(ctx, "Ligue o servidor para receber dos outros e para acessar pelo computador.", 14f, Ui.MUTED))
        server.addView(Ui.primary(ctx, "Configurar servidor") {
            startActivity(Intent(ctx, ServerActivity::class.java))
        }, Ui.lp(ctx))
        root.addView(server)

        val client = Ui.card(ctx)
        client.addView(Ui.text(ctx, "Arquivos de outro aparelho", 18f, Ui.TEXT, true))
        client.addView(Ui.text(ctx, "Navegue pelos arquivos guardados em outro celular servidor.", 14f, Ui.MUTED))
        client.addView(Ui.secondary(ctx, "Conectar a um servidor") {
            startActivity(Intent(ctx, ClientActivity::class.java))
        }, Ui.lp(ctx))
        root.addView(client)

        setContentView(ScrollView(ctx).apply { setBackgroundColor(Ui.SIDE); addView(root) })
    }

    private fun sendText() {
        val t = msg.text.toString().trim()
        if (t.isEmpty()) {
            Toast.makeText(this, "Escreva um texto ou cole um link", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, t)
        })
        msg.setText("")
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, 41)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41 || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val cd = data.clipData
        if (cd != null) {
            for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri)
        } else data.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        startActivity(Intent(this, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = cd ?: ClipData.newRawUri("", uris[0])
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}
