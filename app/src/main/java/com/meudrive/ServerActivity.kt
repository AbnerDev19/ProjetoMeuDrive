package com.meudrive

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

class ServerActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var pw: EditText

    private fun prefs() = getSharedPreferences("cfg", 0)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(20))
        }
        fun text(t: String, size: Float = 15f) = TextView(this).apply {
            text = t; textSize = size; setPadding(0, dp(8), 0, dp(8))
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { onClick() }
        }

        col.addView(text("Meu Drive", 26f))
        status = text("")
        col.addView(status)

        pw = EditText(this).apply {
            hint = "Defina uma senha (mín. 6 caracteres)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        col.addView(pw)
        col.addView(button("Salvar senha") {
            val p = pw.text.toString()
            if (p.length < 6) toast("A senha precisa ter pelo menos 6 caracteres")
            else {
                prefs().edit().putString("pwhash", Cfg.hash(p)).apply()
                pw.setText("")
                toast("Senha salva")
                refresh()
            }
        })

        toggle = button("Ligar servidor") { toggleServer() }
        col.addView(toggle)

        col.addView(button("1) Permitir acesso a todos os arquivos") {
            val i = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(i)
        })
        col.addView(button("2) Não otimizar bateria (manter 24h)") {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        })

        col.addView(text(
            "Acesso de fora de casa: instale o Tailscale (gratuito) no celular e no PC, " +
                "entre na mesma conta e use o endereço 100.x.x.x do celular no navegador do PC, " +
                "ex.: http://100.x.x.x:8080. Não abra a porta 8080 no roteador."
        ))

        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun toggleServer() {
        if (prefs().getBoolean("on", false)) {
            stopService(Intent(this, ServerService::class.java))
            prefs().edit().putBoolean("on", false).apply()
        } else {
            if (prefs().getString("pwhash", null) == null) {
                toast("Defina uma senha primeiro"); return
            }
            startForegroundService(Intent(this, ServerService::class.java))
        }
        status.postDelayed({ refresh() }, 600)
    }

    private fun refresh() {
        val on = prefs().getBoolean("on", false)
        toggle.text = if (on) "Desligar servidor" else "Ligar servidor"
        val allFiles = Environment.isExternalStorageManager()
        val battery = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        val sb = StringBuilder()
        sb.append(if (on) "Servidor LIGADO\n" else "Servidor desligado\n")
        if (on) ips().forEach { sb.append("http://").append(it).append(":").append(Cfg.PORT).append("\n") }
        sb.append("\nPasta dos arquivos:\n").append(Cfg.rootDir(this).path)
        sb.append("\n\nAcesso a todos os arquivos: ").append(if (allFiles) "OK" else "pendente (passo 1)")
        sb.append("\nBateria sem restrição: ").append(if (battery) "OK" else "pendente (passo 2)")
        sb.append("\nSenha: ").append(if (prefs().getString("pwhash", null) != null) "definida" else "pendente")
        status.text = sb.toString()
    }

    private fun ips(): List<String> {
        val out = mutableListOf<String>()
        try {
            NetworkInterface.getNetworkInterfaces().toList().forEach { ni ->
                if (ni.isUp && !ni.isLoopback) ni.inetAddresses.toList().forEach { a ->
                    if (a is Inet4Address) out += a.hostAddress ?: ""
                }
            }
        } catch (_: Exception) {}
        return out.filter { it.isNotEmpty() }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
