package com.meudrive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val p = ctx.getSharedPreferences("cfg", 0)
        if (p.getBoolean("on", false) && p.getString("pwhash", null) != null) {
            try { ctx.startForegroundService(Intent(ctx, ServerService::class.java)) }
            catch (_: Exception) { p.edit().putString("server_error", "Abra o MeuDrive e ligue o servidor após reiniciar.").apply() }
        }
    }
}
