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
    private lateinit var devName: EditText

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

        val mode = Ui.card(ctx)
        mode.addView(Ui.text(ctx, "Galaxy A06 • nuvem da casa", 18f, Ui.TEXT, true))
        mode.addView(Ui.text(ctx, "No A06, marque este aparelho como principal. Nos demais, conecte-se ao IP dele. Todos podem enviar e baixar arquivos pela rede.", 13f, Ui.MUTED))
        mode.addView(android.widget.Switch(ctx).apply {
            text = "Este é o servidor principal"
            isChecked = prefs().getBoolean("primary_server", false)
            setOnCheckedChangeListener { _, checked -> prefs().edit().putBoolean("primary_server", checked).apply() }
        }, Ui.lp(ctx, 10, -1))
        mode.addView(android.widget.Switch(ctx).apply {
            text = "Compartilhar armazenamento interno"
            isChecked = prefs().getBoolean("shared_storage", false)
            setOnCheckedChangeListener { button, checked ->
                if (ServerService.running && checked != prefs().getBoolean("shared_storage", false)) { button.isChecked = !checked; toast("Desligue o servidor antes de mudar a pasta") }
                else if (checked && !Environment.isExternalStorageManager()) {
                    button.isChecked = false; toast("Permita acesso aos arquivos primeiro")
                } else prefs().edit().putBoolean("shared_storage", checked).apply()
            }
        }, Ui.lp(ctx, 8, -1))
        mode.addView(Ui.text(ctx, "Desativado: compartilha somente MeuDrive. Ativado: compartilha fotos, downloads e pastas permitidas do armazenamento interno. Android/data e arquivos privados dos apps ficam protegidos pelo Android.", 12f, Ui.MUTED))
        root.addView(mode)

        val c1 = Ui.card(ctx).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(ctx, 16) }
        status = Ui.text(ctx, "", 16f, Ui.TEXT, true)
        c1.addView(status)
        toggle = Ui.primary(ctx, "Ligar servidor") { toggleServer() }
        c1.addView(toggle, Ui.lp(ctx))
        c1.addView(Ui.secondary(ctx, "Abrir arquivos no navegador do app") {
            if (ServerService.running) startActivity(Intent(ctx, ClientActivity::class.java).putExtra("url", "http://127.0.0.1:${Cfg.PORT}"))
            else toast("Ligue o servidor primeiro")
        }, Ui.lp(ctx, 8, -1))
        root.addView(c1)

        val cT = Ui.card(ctx)
        cT.addView(Ui.text(ctx, "Enviar para o computador", 16f, Ui.TEXT, true))
        cT.addView(Ui.text(
            ctx,
            "Escolha vídeos, fotos ou arquivos do celular. Eles vão para a pasta Transferências e " +
                "você baixa pelo navegador do PC. Também dá para usar Compartilhar > Meu Drive direto na galeria.",
            14f, Ui.MUTED
        ))
        cT.addView(Ui.primary(ctx, "Escolher arquivos do celular") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(i, 40)
        }, Ui.lp(ctx))
        root.addView(cT)

        val cN = Ui.card(ctx)
        cN.addView(Ui.text(ctx, "Nome deste aparelho", 16f, Ui.TEXT, true))
        cN.addView(Ui.text(ctx, "É assim que ele aparece para os outros aparelhos da casa.", 14f, Ui.MUTED))
        devName = EditText(ctx).apply {
            hint = "Ex.: Celular da sala"
            setText(Cfg.deviceName(ctx))
            setSingleLine()
            setTextColor(Ui.TEXT)
            background = Ui.shape(ctx, Ui.SIDE, 10, Ui.LINE)
            setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 12))
        }
        cN.addView(devName, Ui.lp(ctx, 8, -1))
        cN.addView(Ui.secondary(ctx, "Salvar nome") {
            val n = devName.text.toString().trim()
            if (n.isEmpty()) toast("Digite um nome")
            else {
                prefs().edit().putString("devname", n).apply()
                toast("Nome salvo. Desligue e ligue o servidor para valer.")
            }
        }, Ui.lp(ctx))
        root.addView(cN)

        val c2 = Ui.card(ctx)
        c2.addView(Ui.text(ctx, "Senha da casa", 16f, Ui.TEXT, true))
        c2.addView(Ui.text(ctx, "Use a MESMA senha em todos os aparelhos para eles poderem se enviar coisas.", 14f, Ui.MUTED))
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
        c3.addView(Ui.text(ctx, "Servidor dedicado no A06", 16f, Ui.TEXT, true))
        checks = Ui.text(ctx, "", 14f, Ui.MUTED)
        c3.addView(checks)
        c3.addView(Ui.text(ctx, "Deixe no carregador, em local ventilado, e no Wi-Fi do roteador. Na Samsung: Bateria → Limites de uso em segundo plano → Aplicativos nunca suspensos → MeuDrive. Não é necessário manter a tela ligada. Reserve espaço livre para o Android; o app não força CPU em 100%.", 13f, Ui.MUTED))
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
        c3.addView(Ui.primary(ctx, "Abrir modo dedicado e reduzir distrações") {
            startActivity(Intent(ctx, DedicatedActivity::class.java))
        }, Ui.lp(ctx, 10, -1))
        root.addView(c3)

        val c4 = Ui.card(ctx)
        c4.addView(Ui.text(ctx, "Acessar de fora de casa", 16f, Ui.TEXT, true))
        c4.addView(Ui.text(
            ctx,
            "Instale o Tailscale (gratuito) neste celular e no outro aparelho, entre na mesma conta " +
                "e use o endereço 100.x.x.x:8080. Não abra a porta 8080 no roteador.",
            14f, Ui.MUTED
        ))
        c4.addView(Ui.primary(ctx, "Configurar VPN ou link HTTPS") {
            startActivity(Intent(ctx, RemoteAccessActivity::class.java))
        }, Ui.lp(ctx, 8, -1))
        root.addView(c4)

        setContentView(ScrollView(ctx).apply { setBackgroundColor(Ui.SIDE); addView(root) })

        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 40 || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val cd = data.clipData
        if (cd != null) {
            for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri)
        } else data.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        val i = Intent(this, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = cd ?: android.content.ClipData.newRawUri("", uris[0])
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(i)
    }

    private fun toggleServer() {
        if (ServerService.running) {
            stopService(Intent(this, ServerService::class.java))
            prefs().edit().putBoolean("on", false).apply()
        } else {
            if (prefs().getString("pwhash", null) == null) {
                toast("Defina uma senha primeiro"); return
            }
            prefs().edit().remove("server_error").apply()
            startForegroundService(Intent(this, ServerService::class.java))
        }
        status.postDelayed({ refresh() }, 600)
    }

    private fun refresh() {
        val on = ServerService.running
        toggle.text = if (on) "Desligar servidor" else "Ligar servidor"
        val sb = StringBuilder(if (on) "● Servidor ligado" else "○ Servidor desligado")
        if (on) ips().forEach { sb.append("\nhttp://").append(it).append(":").append(Cfg.PORT) }
        if (!on) prefs().getString("server_error", null)?.let { sb.append("\n").append(it) }
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
