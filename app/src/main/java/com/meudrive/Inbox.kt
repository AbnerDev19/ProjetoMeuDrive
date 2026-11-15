package com.meudrive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class InboxItem(
    val id: Long,
    val kind: String,   // "text" ou "file"
    val text: String,   // texto/link, ou nome do arquivo
    val from: String,
    val time: Long,
    val path: String    // só para arquivos: caminho dentro do Meu Drive
)

/** Caixa de entrada: tudo que outros aparelhos mandaram para este. */
object Inbox {
    private const val MAX = 200

    private fun file(ctx: Context) = File(ctx.filesDir, "inbox.json")

    @Synchronized
    fun all(ctx: Context): List<InboxItem> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { k ->
                val o = a.getJSONObject(k)
                InboxItem(
                    o.getLong("id"), o.optString("kind", "text"), o.optString("text"),
                    o.optString("from"), o.optLong("time"), o.optString("path")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    @Synchronized
    fun add(ctx: Context, kind: String, text: String, from: String, path: String = ""): InboxItem {
        val cur = all(ctx)
        val now = System.currentTimeMillis()
        val id = maxOf(now, (cur.firstOrNull()?.id ?: 0L) + 1)
        val item = InboxItem(id, kind, text, from, now, path)
        save(ctx, (listOf(item) + cur).take(MAX))
        return item
    }

    @Synchronized
    fun remove(ctx: Context, id: Long) {
        save(ctx, all(ctx).filter { it.id != id })
    }

    private fun save(ctx: Context, list: List<InboxItem>) {
        val a = JSONArray()
        list.forEach { a.put(toJson(it)) }
        file(ctx).writeText(a.toString())
    }

    fun toJson(i: InboxItem): JSONObject = JSONObject()
        .put("id", i.id).put("kind", i.kind).put("text", i.text)
        .put("from", i.from).put("time", i.time).put("path", i.path)
}

object Notifier {
    private const val CH = "recebidos"

    fun received(ctx: Context, title: String, body: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Recebidos de outros aparelhos", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, InboxActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CH)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        if (!nm.areNotificationsEnabled()) return
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }
}
