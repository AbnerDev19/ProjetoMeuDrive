package com.meudrive

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.app.AlertDialog

class ClientActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var addr: EditText
    private var discovery: Discovery? = null
    private var chooserCb: ValueCallback<Array<Uri>>? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", 0)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(32), 0, 0)
            setBackgroundColor(Ui.BG)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        addr = EditText(this).apply {
            hint = "IP do A06 (ex.: 192.168.1.10:8080)"
            setText(intent.getStringExtra("url") ?: prefs.getString("client_url", ""))
            setSingleLine()
            setTextColor(Ui.TEXT)
            background = Ui.shape(context, Ui.SIDE, 22, Ui.LINE)
            setPadding(dp(16), 0, dp(16), 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val go = Ui.primary(this, "Conectar") { connect() }
        bar.addView(addr, LinearLayout.LayoutParams(0, dp(44), 1f))
        bar.addView(go, LinearLayout.LayoutParams(-2, dp(44)).apply { leftMargin = dp(8) })

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = true
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame)
                    Toast.makeText(
                        this@ClientActivity,
                        "Não foi possível conectar. Confira o endereço e se o servidor está ligado.",
                        Toast.LENGTH_LONG
                    ).show()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                w: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                chooserCb?.onReceiveValue(null)
                chooserCb = cb
                val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                startActivityForResult(Intent.createChooser(i, "Escolher arquivos"), 30)
                return true
            }
        }
        web.setDownloadListener { url, _, contentDisposition, mime, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mime)
            val req = DownloadManager.Request(Uri.parse(url))
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(this, "Baixando $name (pasta Downloads)", Toast.LENGTH_SHORT).show()
        }

        val find = Ui.secondary(this, "Encontrar servidores no Wi-Fi") {
            val peers = discovery?.peers().orEmpty()
            if (peers.isEmpty()) Toast.makeText(this, "Procurando… Confira se o servidor está ligado. Você pode digitar o IP acima.", Toast.LENGTH_LONG).show()
            else AlertDialog.Builder(this).setTitle("Escolha o servidor principal")
                .setItems(peers.map { it.name }.toTypedArray()) { _, which ->
                    val peer = peers[which]; addr.setText("http://${peer.host}:${peer.port}"); connect()
                }.setNegativeButton("Cancelar", null).show()
        }
        col.addView(bar)
        col.addView(find, LinearLayout.LayoutParams(-1, dp(44)))
        col.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(col)

        if (addr.text.isNotBlank()) connect()
    }

    override fun onResume() {
        super.onResume()
        discovery = Discovery(this).also { it.start() }
    }
    override fun onPause() { discovery?.stop(); discovery = null; super.onPause() }
    override fun onDestroy() {
        chooserCb?.onReceiveValue(null); chooserCb = null
        web.destroy(); super.onDestroy()
    }
    private fun connect() {
        try {
            var value = addr.text.toString().trim()
            if (value.isEmpty()) return
            if (!value.contains("://")) value = "http://$value"
            val parsed = java.net.URI(value)
            require(parsed.scheme in listOf("http", "https") && parsed.host != null && parsed.userInfo == null)
            val uri = java.net.URI(parsed.scheme, null, parsed.host, if (parsed.port < 0) Cfg.PORT else parsed.port, "/", null, null)
            val url = uri.toString()
            if (parsed.host != "127.0.0.1") getSharedPreferences("cfg", 0).edit().putString("client_url", url).apply()
            web.loadUrl(url)
        } catch (_: Exception) { Toast.makeText(this, "Digite um endereço válido, como 192.168.1.10:8080", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 30) {
            val selected = if (resultCode != RESULT_OK || data == null) null else data.clipData?.let { cd ->
                Array(cd.itemCount) { cd.getItemAt(it).uri }
            } ?: data.data?.let { arrayOf(it) }
            chooserCb?.onReceiveValue(selected)
            chooserCb = null
        }
    }
}
