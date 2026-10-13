package com.meudrive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = ctx.getSharedPreferences("cfg", 0)
        if (p.getBoolean("on", false) && p.getString("pwhash", null) != null) {
            ctx.startForegroundService(Intent(ctx, ServerService::class.java))
        }
    }
}
