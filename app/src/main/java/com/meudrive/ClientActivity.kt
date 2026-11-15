package com.meudrive

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class ClientActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var addr: EditText
    private lateinit var status: TextView
    private var discovery: Discovery? = null
    private var chooserCb: ValueCallback<Array<Uri>>? = null
    private var currentOrigin: String? = null
    private var mode = "lan"
    private val prefs get() = getSharedPreferences("cfg", 0)
    private fun dp(v: Int) = Ui.dp(this, v)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        mode = intent.getStringExtra("mode") ?: prefs.getString("active_server_mode", "lan") ?: "lan"
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(4)) }
        addr = EditText(this).apply {
            hint = if (mode == "remote") "Link HTTPS ou IP Tailscale do A06" else "IP do A06 (ex.: 192.168.1.10:8080)"
            setText(intent.getStringExtra("url") ?: saved(mode)); setSingleLine(); textSize = 13f
            setTextColor(Ui.TEXT); background = Ui.shape(context, Ui.SIDE, 12, Ui.LINE)
            setPadding(dp(12), 0, dp(12), 0)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        bar.addView(addr, LinearLayout.LayoutParams(0, dp(48), 1f))
        bar.addView(Ui.primary(this, "Abrir") { connect() }, LinearLayout.LayoutParams(-2, dp(48)).apply { leftMargin = dp(6) })
        col.addView(bar)
        val options = LinearLayout(this).apply { setPadding(dp(8), 0, dp(8), 0) }
        options.addView(Ui.secondary(this, "Encontrar no Wi-Fi") { find() }.apply { textSize = 11f }, LinearLayout.LayoutParams(0, dp(44), 1f))
        options.addView(Ui.secondary(this, "Fora de casa") { startActivity(Intent(this, RemoteAccessActivity::class.java)) }.apply { textSize = 11f }, LinearLayout.LayoutParams(0, dp(44), 1f))
        options.addView(Ui.secondary(this, "Repetir") { web.reload() }.apply { textSize = 11f }, LinearLayout.LayoutParams(-2, dp(44)))
        col.addView(options)
        status = Ui.text(this, "Selecione o servidor para ver os arquivos.", 11f, Ui.MUTED).apply { setPadding(dp(12), dp(4), dp(12), dp(4)) }
        col.addView(status)
        web = WebView(this)
        web.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; allowFileAccess = false; allowContentAccess = true }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, icon: android.graphics.Bitmap?) { status.text = "Conectando…" }
            override fun onPageFinished(v: WebView, url: String) { if (status.text == "Conectando…") status.text = "${if (mode == "remote") "Fora de casa" else "Wi-Fi"} • ${currentOrigin.orEmpty()}" }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) status.text = "Não foi possível conectar. Verifique o A06 e use Repetir, Wi-Fi ou Fora de casa."
            }
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val parsed = request.url
                if (parsed.scheme !in listOf("http", "https")) return true
                if (currentOrigin?.let { ServerAddress.parse(it).accepts(parsed.toString()) } == true) return false
                if (request.hasGesture()) runCatching { startActivity(Intent(Intent.ACTION_VIEW, parsed)) }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                chooserCb?.onReceiveValue(null); chooserCb = cb
                startActivityForResult(Intent.createChooser(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }, "Escolher arquivos para o servidor"), 30)
                return true
            }
        }
        web.setDownloadListener { url, _, contentDisposition, mime, _ ->
            try {
                val parsed = Uri.parse(url)
                require(currentOrigin?.let { ServerAddress.parse(it).accepts(url) } == true) { "Download de outro servidor bloqueado" }
                val name = URLUtil.guessFileName(url, contentDisposition, mime)
                val request = DownloadManager.Request(parsed)
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, "Baixando $name em Downloads", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { status.text = e.message ?: "Não foi possível baixar" }
        }
        col.addView(web, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(col)
        if (addr.text.isNotBlank()) connect()
    }
    private fun saved(which: String): String = prefs.getString(if (which == "remote") "remote_url" else "lan_url", null)
        ?: if (which == "lan") prefs.getString("client_url", "").orEmpty().takeIf { raw ->
            runCatching { val server = ServerAddress.parse(raw); !server.secure && !server.privateVpn }.getOrDefault(false)
        }.orEmpty() else ""
    private fun find() {
        val peers = discovery?.peers().orEmpty()
        if (peers.isEmpty()) { Toast.makeText(this, "Procurando… Ligue o servidor no mesmo Wi-Fi ou informe o IP.", Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(this).setTitle("Servidores no Wi-Fi").setItems(peers.map { it.name }.toTypedArray()) { _, which ->
            mode = "lan"; val p = peers[which]; addr.setText("http://${p.host}:${p.port}"); connect()
        }.setNeutralButton("Endereço Wi-Fi salvo") { _, _ -> mode = "lan"; addr.setText(saved("lan")); connect() }.setNegativeButton("Cancelar", null).show()
    }
    override fun onResume() { super.onResume(); discovery = Discovery(this).also { it.start() } }
    override fun onPause() { discovery?.stop(); discovery = null; super.onPause() }
    override fun onDestroy() { chooserCb?.onReceiveValue(null); chooserCb = null; web.destroy(); super.onDestroy() }
    private fun connect() {
        try {
            val server = ServerAddress.parse(addr.text.toString())
            currentOrigin = server.origin
            if (server.host != "127.0.0.1" && server.host != "localhost" && server.host != "[::1]") {
                if (server.secure || server.privateVpn) mode = "remote"
                prefs.edit().putString(if (mode == "remote") "remote_url" else "lan_url", server.origin)
                    .putString("client_url", server.origin).putString("active_server_mode", mode).apply()
            }
            addr.setText(server.origin); web.loadUrl(server.origin + "/")
        } catch (e: Exception) { status.text = e.message ?: "Endereço inválido" }
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 30) {
            val selected = if (resultCode != RESULT_OK || data == null) null else data.clipData?.let { cd -> Array(cd.itemCount) { cd.getItemAt(it).uri } } ?: data.data?.let { arrayOf(it) }
            chooserCb?.onReceiveValue(selected); chooserCb = null
        }
    }
}
