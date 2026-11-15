package com.meudrive

import android.app.Activity
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class RemoteAccessActivity : Activity() {
    private lateinit var endpoint: EditText
    private lateinit var status: TextView
    private lateinit var vpn: TextView
    private val prefs get() = getSharedPreferences("cfg", 0)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 16), Ui.dp(context, 24), Ui.dp(context, 16), Ui.dp(context, 24))
        }
        root.addView(Ui.text(this, "Acessar fora de casa", 25f, Ui.TEXT, true))
        root.addView(Ui.text(this, "Escolha uma conexão particular por VPN ou salve o link HTTPS do seu site de acesso.", 14f, Ui.MUTED))
        val tailscale = Ui.card(this)
        tailscale.addView(Ui.text(this, "A06 sozinho • VPN particular", 18f, Ui.TEXT, true))
        tailscale.addView(Ui.text(this, "Instale Tailscale no A06 e no aparelho remoto, entre na mesma conta e ative a conexão nos dois. No uso pessoal, consulte o plano gratuito do provedor. O MeuDrive não cria uma VPN automaticamente.", 13f, Ui.MUTED))
        tailscale.addView(Ui.primary(this, "Abrir ou instalar Tailscale") {
            val open = packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
            if (open != null) startActivity(open)
            else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://tailscale.com/download/android")))
        }, Ui.lp(this, 8, -1))
        vpn = Ui.text(this, "", 12f, Ui.MUTED)
        tailscale.addView(vpn)
        tailscale.addView(Ui.text(this, "Copie o IP 100.x.x.x do A06 no Tailscale e use http://100.x.x.x:8080. A descoberta automática só funciona no Wi-Fi local. Mantenha os dois aparelhos conectados à internet.", 13f, Ui.MUTED))
        root.addView(tailscale)
        val site = Ui.card(this)
        site.addView(Ui.text(this, "Navegador de qualquer lugar • HTTPS", 18f, Ui.TEXT, true))
        site.addView(Ui.text(this, "O pacote para PC inclui o assistente de acesso remoto gratuito. Execute-o em um PC na mesma rede do A06 para gerar um link HTTPS e cole o link abaixo. O A06 e esse PC precisam continuar ligados. Não exponha a porta 8080 no roteador.", 13f, Ui.MUTED))
        site.addView(Ui.text(this, "Endereço remoto confiável", 13f, Ui.TEXT, true))
        endpoint = EditText(this).apply {
            hint = "https://seu-link ou http://100.x.x.x:8080"
            setText(prefs.getString("remote_url", "")); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        site.addView(endpoint, Ui.lp(this, 8, -1))
        status = Ui.text(this, "Informe o endereço gerado por você. A senha só será enviada quando você entrar no servidor ou confirmar um envio.", 12f, Ui.MUTED)
        site.addView(status)
        site.addView(Ui.secondary(this, "Testar endereço (sem enviar senha)") { test() }, Ui.lp(this, 8, -1))
        site.addView(Ui.primary(this, "Salvar endereço remoto e abrir") {
            try {
                val server = ServerAddress.parse(endpoint.text.toString())
                require(server.secure || server.privateVpn) { "Fora de casa, use HTTPS ou o IP 100.x do seu Tailscale" }
                android.app.AlertDialog.Builder(this).setTitle("Confirmar seu servidor")
                    .setMessage("Você reconhece este endereço como seu servidor?\n\n${server.origin}\n\nAo entrar ou enviar arquivos, ele receberá os dados e a senha da casa.")
                    .setPositiveButton("Salvar e abrir") { _, _ ->
                        prefs.edit().putString("remote_url", server.origin).putString("active_server_mode", "remote").apply()
                        startActivity(Intent(this, ClientActivity::class.java).putExtra("url", server.origin).putExtra("mode", "remote"))
                    }.setNegativeButton("Cancelar", null).show()
            } catch (e: Exception) { toast(e.message ?: "Endereço inválido") }
        }, Ui.lp(this, 8, -1))
        root.addView(site)
        root.addView(Ui.secondary(this, "Voltar à conexão pelo Wi-Fi") {
            prefs.edit().putString("active_server_mode", "lan").apply()
            startActivity(Intent(this, ClientActivity::class.java).putExtra("mode", "lan"))
        }, Ui.lp(this, 8, -1))
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.SIDE); addView(root) })
    }
    override fun onResume() {
        super.onResume()
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        vpn.text = if (active) "Uma VPN está ativa neste aparelho. Isso não confirma que o A06 está conectado; teste o endereço." else "Nenhuma VPN detectada neste aparelho."
    }
    private fun test() {
        val server = try { ServerAddress.parse(endpoint.text.toString()) } catch (e: Exception) { toast(e.message ?: "Endereço inválido"); return }
        status.text = "Testando ${server.origin}…"
        thread {
            val result = try {
                val c = URL(server.origin + "/api/whoami").openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 8000; c.readTimeout = 8000; c.instanceFollowRedirects = false
                    when (c.responseCode) {
                        200 -> "Endereço respondeu. Abra o site e confirme que é seu servidor."
                        401 -> "Endereço respondeu e pede autenticação. Abra e entre com a senha da casa."
                        in 300..399 -> "O endereço redireciona. Informe o link final do seu servidor."
                        else -> "O endereço respondeu com erro ${c.responseCode}. Confira o link e o servidor."
                    }
                } finally { c.disconnect() }
            } catch (_: Exception) { "Sem conexão. Confira o A06, a internet, a VPN ou se o PC do link HTTPS está ligado." }
            if (!isFinishing) runOnUiThread { status.text = result }
        }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
