package com.meudrive

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 56), Ui.dp(ctx, 20), Ui.dp(ctx, 24))
        }
        root.addView(Ui.text(ctx, "Meu Drive", 30f, Ui.TEXT, true))
        root.addView(Ui.text(ctx, "Seus arquivos, no seu celular.", 15f, Ui.MUTED))

        val server = Ui.card(ctx).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(ctx, 24) }
        server.addView(Ui.text(ctx, "Servidor", 18f, Ui.TEXT, true))
        server.addView(Ui.text(ctx, "Este celular guarda os arquivos e fica ligado o tempo todo.", 14f, Ui.MUTED))
        server.addView(Ui.primary(ctx, "Usar como servidor") {
            startActivity(Intent(ctx, ServerActivity::class.java))
        }, Ui.lp(ctx))
        root.addView(server)

        val client = Ui.card(ctx)
        client.addView(Ui.text(ctx, "Cliente", 18f, Ui.TEXT, true))
        client.addView(Ui.text(ctx, "Acesse os arquivos que estão em outro celular servidor.", 14f, Ui.MUTED))
        client.addView(Ui.secondary(ctx, "Conectar a um servidor") {
            startActivity(Intent(ctx, ClientActivity::class.java))
        }, Ui.lp(ctx))
        root.addView(client)

        setContentView(ScrollView(ctx).apply { setBackgroundColor(Ui.SIDE); addView(root) })
    }
}
