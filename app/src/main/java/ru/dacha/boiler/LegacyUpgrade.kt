package ru.dacha.boiler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/** One-time cleanup when updating from a version with remote control. */
internal object LegacyUpgrade {
    fun apply(context: Context, prefs: SharedPreferences) {
        if (prefs.getBoolean("smtpOnlyMigrated", false)) return
        // Preserve an explicit stop/pause and require mail setup for former non-mail modes.
        val editor = prefs.edit()
        val stopMonitoring = prefs.getBoolean("paused", false) || !prefs.getBoolean("emailPhotos", true)
        if (stopMonitoring) {
            editor.putBoolean("enabled", false).putLong("pendingCapture", 0)
        }
        for (key in prefs.all.keys) {
            if (key.startsWith("telegram", true) || key.startsWith("heartbeat", true) ||
                key in listOf("emailPhotos", "remoteControl", "remotePhoto", "paused", "pairCode", "pairUntil",
                    "lastTelegramPhoto", "lastHeartbeat")) editor.remove(key)
        }
        if (prefs.getString("problem", "")?.startsWith("Telegram") == true) editor.remove("problem")
        val alarms = context.getSystemService(AlarmManager::class.java)
        val obsolete = listOf(3 to "telegram", 4 to "heartbeat") +
            if (stopMonitoring) listOf(1 to "daily", 2 to "retry") else emptyList()
        for ((code, action) in obsolete) {
            val pending = PendingIntent.getBroadcast(context, code,
                Intent(context, AlarmReceiver::class.java).setAction(action),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pending != null) { alarms.cancel(pending); pending.cancel() }
        }
        check(editor.putBoolean("smtpOnlyMigrated", true).commit()) { "Не удалось обновить настройки" }
    }
}
