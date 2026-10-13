package com.meudrive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import fi.iki.elonen.NanoHTTPD

class ServerService : Service() {

    private var server: DriveServer? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("drive", "Servidor Meu Drive", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ServerActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = Notification.Builder(this, "drive")
            .setContentTitle("Meu Drive ligado")
            .setContentText("Servidor na porta " + Cfg.PORT)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notif)

        if (server == null) {
            try {
                server = DriveServer(this).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "meudrive:server").also { it.acquire() }
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "meudrive:wifi")
                    .also { it.acquire() }
                getSharedPreferences("cfg", 0).edit().putBoolean("on", true).apply()
            } catch (e: Exception) {
                getSharedPreferences("cfg", 0).edit().putBoolean("on", false).apply()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
