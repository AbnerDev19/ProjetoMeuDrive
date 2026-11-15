package com.meudrive

import android.app.NotificationManager
import android.content.Context

/** Only changes interruption settings after an explicit user action; restores the prior filter. */
object DedicatedMode {
    fun enabled(ctx: Context) = ctx.getSharedPreferences("dedicated", 0).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, enabled: Boolean) {
        val prefs = ctx.getSharedPreferences("dedicated", 0)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        require(nm.isNotificationPolicyAccessGranted) { "Autorize o acesso a Não perturbe antes" }
        if (enabled) {
            if (!prefs.getBoolean("enabled", false)) {
                val previous = nm.currentInterruptionFilter
                require(previous != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) { "Não foi possível ler o modo atual" }
                // Persist before changing the system so a process interruption does not lose recovery information.
                check(prefs.edit().putInt("previous_filter", previous).putBoolean("enabled", true).commit()) { "Não foi possível guardar a configuração anterior" }
                try { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE) }
                catch (e: Exception) { prefs.edit().putBoolean("enabled", false).commit(); throw e }
            }
        } else {
            if (prefs.getBoolean("enabled", false) && nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE) {
                nm.setInterruptionFilter(prefs.getInt("previous_filter", NotificationManager.INTERRUPTION_FILTER_ALL))
            }
            prefs.edit().putBoolean("enabled", false).remove("previous_filter").commit()
        }
    }
}
