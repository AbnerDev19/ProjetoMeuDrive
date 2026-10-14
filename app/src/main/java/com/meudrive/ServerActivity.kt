package com.meudrive

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
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
    private lateinit var checks: TextView
    private lateinit var toggle: Button
    private lateinit var pw: EditText

    private fun prefs() = getSharedPreferences("cfg", 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 44), Ui.dp(ctx, 16), Ui.dp(ctx, 24))
        }
        root.addView(Ui.text(ctx, "Servidor", 26f, Ui.TEXT, true))
        root.addView(Ui.text(ctx, "Este celular guarda seus arquivos.", 14f, Ui.MUTED))

        val c1 = Ui.card(ctx).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(ctx, 16) }
        status = Ui.text(ctx, "", 16f, Ui.TEXT, true)
        c1.addView(status)
        toggle = Ui.primary(ctx, "Ligar servidor") { toggleServer() }
        c1.addView(toggle, Ui.lp(ctx))
        root.addView(c1)

        val c2 = Ui.card(ctx)
        c2.addView(Ui.text(ctx, "Senha de acesso", 16f, Ui.TEXT, true))
        pw = EditText(ctx).apply {
            hint = "Mínimo 6 caracteres"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Ui.TEXT)
            background = Ui.shape(ctx, Ui.SIDE, 10, Ui.LINE)
            setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 12))
        }
        c2.addView(pw, Ui.lp(ctx, 8, -1))
        c2.addView(Ui.secondary(ctx, "Salvar senha") {
            val p = pw.text.toString()
            if (p.length < 6) toast("A senha precisa ter pelo menos 6 caracteres")
            else {
                prefs().edit().putString("pwhash", Cfg.hash(p)).apply()
                pw.setText("")
                toast("Senha salva")
                refresh()
            }
        }, Ui.lp(ctx))
        root.addView(c2)

        val c3 = Ui.card(ctx)
        c3.addView(Ui.text(ctx, "Para ficar ligado 24h", 16f, Ui.TEXT, true))
        checks = Ui.text(ctx, "", 14f, Ui.MUTED)
        c3.addView(checks)
        c3.addView(Ui.secondary(ctx, "1. Permitir acesso aos arquivos") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        }, Ui.lp(ctx))
        c3.addView(Ui.secondary(ctx, "2. Não otimizar bateria") {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }, Ui.lp(ctx))
        root.addView(c3)

        val c4 = Ui.card(ctx)
        c4.addView(Ui.text(ctx, "Acessar de fora de casa", 16f, Ui.TEXT, true))
        c4.addView(Ui.text(
            ctx,
            "Instale o Tailscale (gratuito) neste celular e no outro aparelho, entre na mesma conta " +
                "e use o endereço 100.x.x.x:8080. Não abra a porta 8080 no roteador.",
            14f, Ui.MUTED
        ))
        root.addView(c4)

        setContentView(ScrollView(ctx).apply { setBackgroundColor(Ui.SIDE); addView(root) })

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
        val sb = StringBuilder(if (on) "● Servidor ligado" else "○ Servidor desligado")
        if (on) ips().forEach { sb.append("\nhttp://").append(it).append(":").append(Cfg.PORT) }
        status.text = sb.toString()
        status.setTextColor(if (on) Color.parseColor("#0F9D58") else Ui.MUTED)

        val allFiles = Environment.isExternalStorageManager()
        val battery = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        val hasPw = prefs().getString("pwhash", null) != null
        checks.text = (if (hasPw) "✓" else "✗") + " Senha definida\n" +
            (if (allFiles) "✓" else "✗") + " Acesso a todos os arquivos\n" +
            (if (battery) "✓" else "✗") + " Bateria sem restrição\n\n" +
            "Pasta: " + Cfg.rootDir(this).path
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
