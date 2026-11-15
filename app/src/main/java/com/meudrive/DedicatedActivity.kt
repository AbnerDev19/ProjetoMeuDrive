package com.meudrive

import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class DedicatedActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val refresh = object : Runnable {
        override fun run() { update(); handler.postDelayed(this, 3000) }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 16), Ui.dp(context, 24), Ui.dp(context, 16), Ui.dp(context, 24))
        }
        root.addView(Ui.text(this, "A06 • servidor dedicado", 25f, Ui.TEXT, true))
        root.addView(Ui.text(this, "Silencie as interrupções e mantenha o MeuDrive à frente. O servidor continua com a tela apagada.", 14f, Ui.MUTED))
        val panel = Ui.card(this)
        status = Ui.text(this, "", 14f, Ui.TEXT)
        panel.addView(status)
        panel.addView(Ui.primary(this, "Configurar ou ligar servidor") { startActivity(Intent(this, ServerActivity::class.java)) }, Ui.lp(this, 12, -1))
        panel.addView(Ui.secondary(this, "Configurar acesso fora de casa") { startActivity(Intent(this, RemoteAccessActivity::class.java)) }, Ui.lp(this, 8, -1))
        root.addView(panel)
        val focus = Ui.card(this)
        focus.addView(Ui.text(this, "Reduzir distrações", 18f, Ui.TEXT, true))
        focus.addView(Ui.text(this, "O modo silencioso ativa Não perturbe total, incluindo avisos, chamadas e alarmes. Ao desativar pelo MeuDrive, o modo anterior é restaurado, desde que você não o tenha alterado pelo sistema.", 13f, Ui.MUTED))
        focus.addView(Ui.secondary(this, "1. Autorizar Não perturbe") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }, Ui.lp(this, 8, -1))
        focus.addView(Ui.primary(this, "Ativar modo silencioso dedicado") {
            try { DedicatedMode.setEnabled(this, true); update() }
            catch (e: Exception) { toast(e.message ?: "Não foi possível ativar") }
        }, Ui.lp(this, 8, -1))
        focus.addView(Ui.secondary(this, "Desativar e restaurar modo anterior") {
            try { DedicatedMode.setEnabled(this, false); update() }
            catch (e: Exception) { toast(e.message ?: "Restaure Não perturbe nas configurações") }
        }, Ui.lp(this, 8, -1))
        root.addView(focus)
        val pin = Ui.card(this)
        pin.addView(Ui.text(this, "Manter este app na tela", 18f, Ui.TEXT, true))
        pin.addView(Ui.text(this, "Ative Fixação de aplicativo nas configurações de segurança e confirme a fixação abaixo. Isso limita a navegação por outros apps enquanto estiver fixado; não encerra processos do Android.", 13f, Ui.MUTED))
        pin.addView(Ui.secondary(this, "Abrir configurações de segurança") { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }, Ui.lp(this, 8, -1))
        pin.addView(Ui.primary(this, "Fixar MeuDrive (confirmação do Android)") {
            try { startLockTask(); handler.postDelayed({ update() }, 800) }
            catch (_: Exception) { toast("Ative Fixação de aplicativo nas configurações de segurança") }
        }, Ui.lp(this, 8, -1))
        pin.addView(Ui.secondary(this, "Sair da fixação") { runCatching { stopLockTask() }; update() }, Ui.lp(this, 8, -1))
        root.addView(pin)
        val limits = Ui.card(this)
        limits.addView(Ui.text(this, "Preparação do aparelho", 18f, Ui.TEXT, true))
        limits.addView(Ui.text(this, "Em Bateria, adicione MeuDrive e Tailscale a Aplicativos nunca suspensos. Remova ou desative manualmente apps que você não usa em Configurações → Aplicativos. O Android não permite a um APK comum desligar todos os outros apps, desativar recursos essenciais ou garantir funcionamento ininterrupto.", 13f, Ui.MUTED))
        limits.addView(Ui.secondary(this, "Abrir lista de aplicativos") { startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)) }, Ui.lp(this, 8, -1))
        root.addView(limits)
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.SIDE); addView(root) })
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    private fun update() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val nm = getSystemService(NotificationManager::class.java)
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10.0)
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        status.text = "${if (ServerService.running) "● Servidor ligado" else "○ Servidor desligado"}\n" +
            "${if (Environment.isExternalStorageManager()) "✓" else "○"} Acesso ao armazenamento\n" +
            "${if (pm.isIgnoringBatteryOptimizations(packageName)) "✓" else "○"} Bateria sem restrição\n" +
            "${if (plugged) "✓ Na alimentação" else "○ Usando a bateria"}\n" +
            "Temperatura da bateria: ${temp?.let { "%.1f °C".format(it) } ?: "indisponível"}\n" +
            "Estado térmico do Android: ${when (pm.currentThermalStatus) { PowerManager.THERMAL_STATUS_NONE -> "normal"; PowerManager.THERMAL_STATUS_LIGHT -> "aquecendo"; else -> "reduza o uso e deixe resfriar" }}\n" +
            "Não perturbe: ${if (DedicatedMode.enabled(this) && nm.isNotificationPolicyAccessGranted && nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE) "dedicado ativo" else "controle do sistema"}\n" +
            "Fixação: ${if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) "ativa" else "desativada"}\n\n" +
            "${Cfg.ips().joinToString("\n") { "http://$it:${Cfg.PORT}" }}"
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
