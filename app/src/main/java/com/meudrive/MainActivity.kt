package com.meudrive

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        col.addView(TextView(this).apply { text = "Meu Drive"; textSize = 28f })
        col.addView(TextView(this).apply {
            text = "Escolha como usar este celular:"
            textSize = 15f
            setPadding(0, pad / 2, 0, pad)
        })
        col.addView(Button(this).apply {
            text = "Servidor (guarda os arquivos, fica ligado)"
            setOnClickListener { startActivity(Intent(this@MainActivity, ServerActivity::class.java)) }
        })
        col.addView(Button(this).apply {
            text = "Cliente (acessar o meu servidor)"
            setOnClickListener { startActivity(Intent(this@MainActivity, ClientActivity::class.java)) }
        })
        setContentView(col)
    }
}
