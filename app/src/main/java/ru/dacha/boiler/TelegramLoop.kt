package ru.dacha.boiler

import android.os.PowerManager
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Long polling is independent of camera/SMTP work, so a slow SMTP server cannot disconnect control. */
class TelegramLoop(private val service: MonitorService) {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var active = true
    @Volatile private var client: TelegramApi? = null
    private var afterFailure = 0L
    private var lastToken = ""
    private var lastClient: TelegramApi? = null
    init { executor.scheduleWithFixedDelay({ tick() }, 0, 5, TimeUnit.SECONDS) }
    private fun tick() {
        val s = Settings(service)
        if (!active) return
        if (!s.enabled && !s.pairing) { service.stopIfIdle(); return }
        if (!s.pairing && !s.usesTelegram) return
        if (System.currentTimeMillis() < afterFailure) return
        var lock: PowerManager.WakeLock? = null
        try {
            val token = s.token()
            if (lastToken != token) { lastToken = token; lastClient = TelegramApi(token) }
            val api = lastClient ?: return
            client = api
            if (s.enabled && s.heartbeat && s.telegramChat > 0 && System.currentTimeMillis() >= s.prefs.getLong("heartbeatNext", 0)) {
                lock = service.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "boiler:heartbeat")
                lock.acquire(45_000)
                api.message(s.telegramChat, RemoteCommands(s, {}, {}).status())
                if (!active || !s.enabled) return
                s.prefs.edit().putLong("heartbeatNext", Schedule.next(s.heartbeatTime, System.currentTimeMillis())).commit()
                s.status("lastHeartbeat", EventLog.stamp())
                Scheduler.plan(service)
            }
            if (s.pairing || (s.enabled && s.remoteControl)) {
                val updates = api.updates(s.prefs.getLong("telegramOffset", 0))
                for (i in 0 until updates.length()) {
                    if (!active || (!s.enabled && !s.pairing)) break
                    val update = updates.getJSONObject(i)
                    val reply = service.command(update).get(180, TimeUnit.SECONDS)
                    if (reply != null && active) api.message(s.telegramChat, reply)
                }
            }
            s.status("telegramProblem", "Нет")
        } catch (e: Exception) {
            if (!active) return
            val problem = if (e is TelegramException) e.message!! else "Telegram: проверьте интернет и настройки бота"
            s.status("telegramProblem", problem)
            // Never print exception URLs: they contain the bot token.
            afterFailure = System.currentTimeMillis() + if (e is TelegramException && e.retryAfter > 0)
                e.retryAfter.toLong().coerceIn(10, 3600) * 1000 else 60_000L
        } finally { if (lock?.isHeld == true) lock.release() }
    }
    fun stop() { active = false; client?.cancel(); executor.shutdownNow() }
}
