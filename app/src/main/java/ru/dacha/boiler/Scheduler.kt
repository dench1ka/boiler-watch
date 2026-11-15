package ru.dacha.boiler

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

object Scheduler {
    private fun alarm(c: Context) = c.getSystemService(AlarmManager::class.java)
    private fun intent(c: Context, retry: Boolean) = signal(c, if (retry) 2 else 1, if (retry) "retry" else "daily")
    private fun signal(c: Context, code: Int, action: String) = PendingIntent.getBroadcast(c, code,
        Intent(c, AlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun exact(c: Context) = Build.VERSION.SDK_INT < 31 || alarm(c).canScheduleExactAlarms()
    // exact() checks the special access; it can also be revoked between checking and scheduling.
    @SuppressLint("ScheduleExactAlarm")
    private fun set(c: Context, time: Long, retry: Boolean) {
        try {
            if (exact(c)) alarm(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, intent(c, retry))
            else alarm(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, intent(c, retry))
        } catch (_: SecurityException) {
            alarm(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, intent(c, retry))
        }
    }
    fun plan(c: Context, reset: Boolean = false) {
        val s = Settings(c)
        if (!s.enabled) { cancel(c); return }
        val now = System.currentTimeMillis()
        if (s.paused || !s.hasPhotoRoute) alarm(c).cancel(intent(c, false))
        else {
            if (reset || s.next <= now) s.next = Schedule.next(s.times, now)
            set(c, s.next, false)
        }
        if (s.usesTelegram) alarm(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 15 * 60_000L, signal(c, 3, "telegram"))
        else alarm(c).cancel(signal(c, 3, "telegram"))
        if (s.heartbeat) {
            val at = maxOf(now + 60_000, s.prefs.getLong("heartbeatNext", 0))
            alarm(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, signal(c, 4, "heartbeat"))
        } else alarm(c).cancel(signal(c, 4, "heartbeat"))
    }
    fun retry(c: Context, delay: Long = 15 * 60_000L) = set(c, System.currentTimeMillis() + delay, true)
    fun cancel(c: Context) {
        alarm(c).cancel(intent(c, false)); alarm(c).cancel(intent(c, true))
        alarm(c).cancel(signal(c, 3, "telegram")); alarm(c).cancel(signal(c, 4, "heartbeat"))
    }
    fun run(c: Context, manual: Boolean = false) {
        val live = MonitorService.instance
        if (live != null) { live.execute(manual); return }
        if (Build.VERSION.SDK_INT < 28) {
            // Bridge the receiver-to-service handoff before the service acquires its own lock.
            val handoff = c.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "boiler:handoff")
            handoff.acquire(15_000L)
            try {
                val intent = Intent(c, MonitorService::class.java).putExtra("manual", manual)
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(intent) else c.startService(intent)
            }
            catch (e: Exception) { EventLog.add(c, "Не удалось запустить сервис: ${e.javaClass.simpleName}"); retry(c) }
            finally { Handler(Looper.getMainLooper()).postDelayed({ if (handoff.isHeld) handoff.release() }, 10_000) }
        } else {
            Settings(c).status("problem", "Откройте приложение и включите наблюдение: Android ограничивает запуск камеры из фона")
            Notices.resume(c)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = Settings(context)
        if (!s.enabled) return
        val now = System.currentTimeMillis()
        if (!s.paused && s.hasPhotoRoute && s.next in 1..now) {
            if (s.pendingCapture == 0L) s.pendingCapture = s.next
            Scheduler.plan(context)
        }
        Scheduler.plan(context)
        Scheduler.run(context)
    }
}

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = Settings(context)
        if (!s.enabled) return
        val clockChanged = intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED
        if (!clockChanged && !s.paused && s.hasPhotoRoute && s.next in 1..System.currentTimeMillis() && s.pendingCapture == 0L) s.pendingCapture = s.next
        Scheduler.plan(context, reset = clockChanged)
        EventLog.add(context, "Расписание восстановлено после ${intent.action}")
        Scheduler.run(context)
    }
}
