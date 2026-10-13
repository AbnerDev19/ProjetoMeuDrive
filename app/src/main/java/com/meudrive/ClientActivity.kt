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

class ClientActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var addr: EditText
    private var chooserCb: ValueCallback<Array<Uri>>? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", 0)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(32), 0, 0)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        addr = EditText(this).apply {
            hint = "Endereço do servidor (ex.: 100.64.1.2:8080)"
            setText(prefs.getString("client_url", ""))
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val go = Button(this).apply {
            text = "Conectar"
            setOnClickListener { connect() }
        }
        bar.addView(addr, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(go)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
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

        col.addView(bar)
        col.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(col)

        if (addr.text.isNotBlank()) connect()
    }

    private fun connect() {
        var u = addr.text.toString().trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        val host = u.substringAfter("://")
        if (!host.contains(":")) u = u.trimEnd('/') + ":" + Cfg.PORT
        getSharedPreferences("cfg", 0).edit().putString("client_url", addr.text.toString().trim()).apply()
        web.loadUrl(u)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 30) {
            chooserCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            chooserCb = null
        }
    }
}
