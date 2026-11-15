package com.meudrive

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private var discovery: Discovery? = null
    private lateinit var network: LinearLayout
    private val prefs get() = getSharedPreferences("cfg", 0)

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }
    override fun onResume() {
        super.onResume()
        render()
        discovery = Discovery(this).also { it.onChange = { renderPeers() }; it.start() }
    }
    override fun onPause() { discovery?.stop(); discovery = null; super.onPause() }
    private fun openCloud(url: String? = null) {
        startActivity(Intent(this, ClientActivity::class.java).apply { if (url != null) putExtra("url", url) })
    }
    private fun settings() = startActivity(Intent(this, ServerActivity::class.java))
    private fun render() {
        window.statusBarColor = Ui.SIDE; window.navigationBarColor = Ui.BG
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.SIDE) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 20), Ui.dp(context, 16), Ui.dp(context, 20), Ui.dp(context, 14))
        }
        val logo = Ui.text(this, "☁", 26f, android.graphics.Color.WHITE, true).apply {
            gravity = android.view.Gravity.CENTER; background = Ui.shape(context, Ui.ACCENT, 12)
        }
        header.addView(logo, LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)))
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(context, 12), 0, 0, 0) }
        title.addView(Ui.text(this, "MeuDrive", 22f, Ui.ACCENT, true))
        title.addView(Ui.text(this, "Sua nuvem em casa", 11f, Ui.MUTED))
        header.addView(title); shell.addView(header)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(context, 16), Ui.dp(context, 14), Ui.dp(context, 16), Ui.dp(context, 24))
        }
        content.addView(Ui.text(this, "Tudo conectado, perto de você", 12f, Ui.MUTED))
        content.addView(Ui.text(this, "Meu armazenamento", 26f, Ui.TEXT, true))
        val root = Cfg.rootDir(this)
        val storage = Ui.card(this).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(context, 18) }
        storage.addView(Ui.text(this, "Neste aparelho • ${Cfg.deviceName(this)}", 12f, Ui.MUTED))
        storage.addView(Ui.text(this, "${format(root.totalSpace - root.usableSpace)} de ${format(root.totalSpace)}", 24f, Ui.TEXT, true))
        val percent = if (root.totalSpace > 0) ((root.totalSpace - root.usableSpace) * 100 / root.totalSpace).toInt() else 0
        storage.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = percent; progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
        }, Ui.lp(this, 14, -1))
        storage.addView(Ui.text(this, "${format(root.usableSpace)} livres • ${if (ServerService.running) "Servidor ligado" else "Servidor desligado"}", 11f, Ui.MUTED))
        content.addView(storage)
        content.addView(Ui.text(this, "Ações rápidas", 18f, Ui.TEXT, true))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("↑  Enviar" to { pickFiles() }, "☁  Arquivos" to { openCloud() }, "↙  Recebidos" to {
            startActivity(Intent(this, InboxActivity::class.java))
        }).forEach { (label, action) -> actions.addView(Ui.secondary(this, label, action), LinearLayout.LayoutParams(0, Ui.dp(this, 54), 1f).apply { setMargins(0, Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 20)) }) }
        content.addView(actions)
        val primary = Ui.card(this)
        val localPrimary = prefs.getBoolean("primary_server", false)
        val url = prefs.getString("client_url", "").orEmpty()
        primary.addView(Ui.text(this, "Sua nuvem principal", 18f, Ui.TEXT, true))
        primary.addView(Ui.text(this, if (localPrimary) "Este celular é o servidor principal. Ligue o servidor para guardar e receber arquivos." else if (url.isNotEmpty()) "Servidor salvo: $url" else "Use o Galaxy A06 para guardar os arquivos da casa. Nos outros aparelhos, conecte-se a ele.", 13f, Ui.MUTED))
        primary.addView(Ui.primary(this, if (localPrimary) "Abrir arquivos deste servidor" else "Conectar ao servidor principal") {
            if (localPrimary && ServerService.running) openCloud("http://127.0.0.1:${Cfg.PORT}")
            else if (localPrimary) settings() else openCloud()
        }, Ui.lp(this, 12, -1))
        primary.addView(Ui.secondary(this, if (localPrimary) "Configurar servidor" else "Usar este celular como servidor") { settings() }, Ui.lp(this, 8, -1))
        content.addView(primary)
        content.addView(Ui.text(this, "Aparelhos na rede", 18f, Ui.TEXT, true))
        network = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(network); renderPeers()
        val textCard = Ui.card(this)
        textCard.addView(Ui.text(this, "Compartilhe sem cabo", 16f, Ui.TEXT, true))
        textCard.addView(Ui.text(this, "Na galeria ou no gerenciador de arquivos, toque em Compartilhar → MeuDrive. No PC, abra o endereço mostrado na tela Servidor.", 13f, Ui.MUTED))
        textCard.addView(Ui.secondary(this, "Enviar texto ou link") {
            val input = android.widget.EditText(this).apply { hint = "Cole seu texto ou link" }
            android.app.AlertDialog.Builder(this).setTitle("Enviar texto").setView(input)
                .setPositiveButton("Escolher destino") { _, _ ->
                    if (input.text.isNotBlank()) startActivity(Intent(this, ShareActivity::class.java).apply {
                        action = Intent.ACTION_SEND; type = "text/plain"; putExtra(Intent.EXTRA_TEXT, input.text.toString())
                    })
                }.setNegativeButton("Cancelar", null).show()
        }, Ui.lp(this, 10, -1)); content.addView(textCard)
        shell.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { setBackgroundColor(Ui.BG); setPadding(Ui.dp(context, 8), Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 8)) }
        listOf("Início" to { render() }, "Arquivos" to { openCloud() }, "Recebidos" to { startActivity(Intent(this, InboxActivity::class.java)) }, "Servidor" to { settings() }).forEach { (label, action) ->
            nav.addView(Ui.secondary(this, label, action).apply { textSize = 11f; setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        }
        shell.addView(nav); setContentView(shell)
    }
    private fun renderPeers() {
        network.removeAllViews()
        val peers = discovery?.peers().orEmpty()
        if (peers.isEmpty()) network.addView(Ui.text(this, "Procurando servidores no Wi-Fi… Você também pode informar o endereço manualmente em Arquivos.", 12f, Ui.MUTED), Ui.lp(this, 8, -1))
        peers.forEach { p -> network.addView(Ui.secondary(this, "▣  ${p.name}   →") { openCloud("http://${p.host}:${p.port}") }, Ui.lp(this, 8, -1)) }
    }
    private fun format(bytes: Long) = if (bytes >= 1073741824) "%.1f GB".format(bytes / 1073741824.0) else "%.0f MB".format(bytes / 1048576.0)
    private fun pickFiles() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, 41)
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41 || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) } ?: data.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        startActivity(Intent(this, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE; type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = data.clipData ?: ClipData.newRawUri("", uris[0]); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}
