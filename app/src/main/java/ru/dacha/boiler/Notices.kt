package ru.dacha.boiler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object Notices {
    private fun manager(c: Context) = c.getSystemService(NotificationManager::class.java)
    fun build(c: Context, text: String): Notification {
        if (Build.VERSION.SDK_INT >= 26) manager(c).createNotificationChannel(
            NotificationChannel("monitor", "Наблюдение за котлом", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, "monitor") else Notification.Builder(c)
        return builder.setSmallIcon(ru.dacha.boiler.R.drawable.ic_boiler).setContentTitle("Котёл под присмотром")
            .setContentText(text).setContentIntent(pending).setOngoing(true).build()
    }
    fun resume(c: Context) {
        try { manager(c).notify(2, build(c, "Нажмите, чтобы возобновить наблюдение")) } catch (_: SecurityException) { }
    }
}
