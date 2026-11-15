package ru.dacha.boiler

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MonitorService : Service() {
    companion object {
        @Volatile var instance: MonitorService? = null
            private set
    }
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val runAgain = AtomicBoolean(false)
    val isBusy: Boolean get() = busy.get()
    private val main = Handler(Looper.getMainLooper())
    private var network: NetworkRecovery? = null
    fun stopIfIdle() {
        val s = Settings(this)
        if (!isBusy && !s.enabled) main.post {
            if (!isBusy && !Settings(this).enabled) stopSelf()
        }
    }
    override fun onCreate() {
        super.onCreate()
        val state = Settings(this)
        val notification = Notices.build(this, if (state.enabled) "Наблюдение включено" else "Выполняется проверка камеры")
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            else startForeground(1, notification)
            instance = this
        } catch (e: Exception) {
            Settings(this).status("problem", "Сервис камеры не запущен. Откройте приложение и включите наблюдение")
            EventLog.add(this, "Android запретил сервис камеры: ${e.javaClass.simpleName}")
            stopSelf()
        }
        if (instance === this) try {
            network = NetworkRecovery(this) {
                main.post {
                    if (instance === this && Settings(this).enabled && Outbox(this).count() > 0) {
                        EventLog.add(this, "Интернет восстановлен: отправляем сохранённые фото")
                        execute(false)
                    }
                }
            }
        } catch (_: RuntimeException) {
            EventLog.add(this, "Уведомления о сети недоступны; повторная отправка по таймеру")
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (instance === this) {
            execute(intent?.getBooleanExtra("manual", false) == true, intent?.getBooleanExtra("preview", false) == true)
        }
        return if (Build.VERSION.SDK_INT < 28) START_STICKY else START_NOT_STICKY
    }
    fun execute(manual: Boolean, preview: Boolean = false) {
        if (!busy.compareAndSet(false, true)) {
            if (manual) Settings(this).status("problem", "Предыдущая операция ещё выполняется")
            else if (Settings(this).enabled) {
                runAgain.set(true)
                Scheduler.retry(this)
            }
            return
        }
        val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "boiler:report")
        lock.acquire(240_000L)
        worker.execute {
            val s = Settings(this)
            try {
                val outbox = Outbox(this)
                val now = System.currentTimeMillis()
                if (s.enabled && s.next in 1..now) {
                    if (s.pendingCapture == 0L) s.pendingCapture = s.next
                    Scheduler.plan(this)
                }
                // Catch a crash after atomic save but before committing the captured slot.
                if (s.pendingCapture > 0 && (s.pendingCapture == s.lastCapturedSlot || outbox.hasSlot(s.pendingCapture))) s.pendingCapture = 0
                val slot = s.pendingCapture
                var captureFailed = false
                var fresh: File? = null
                if (manual || (s.enabled && slot > 0)) {
                    try {
                        outbox.checkSpace()
                        val bytes = CameraCapture.take(s)
                        if (preview) java.io.File(filesDir, "preview.jpg").outputStream().use { it.write(bytes); it.fd.sync() }
                        else {
                            fresh = outbox.save(bytes, s, slot)
                        }
                        s.status("lastPhoto", EventLog.stamp())
                        if (!preview) {
                            if (slot > 0) { s.lastCapturedSlot = slot; s.pendingCapture = 0 }
                        }
                        EventLog.add(this, "Фото сохранено${if (manual) " вручную" else " по расписанию"}")
                    } catch (e: Exception) {
                        captureFailed = true
                        // Delivery queue is still processed even if a new capture fails.
                        s.status("problem", e.message ?: "Ошибка камеры")
                        EventLog.add(this, "Съёмка не выполнена: ${e.javaClass.simpleName}")
                        if (!manual && s.enabled) Scheduler.retry(this)
                    }
                }
                // Leave a quiet window before the next capture; do not start another archive
                // upload just before its alarm. Already running network calls may finish later.
                fun yieldArchive(): Boolean = s.enabled && (s.pendingCapture > 0 ||
                    (s.next > 0 && s.next <= System.currentTimeMillis() + 120_000L))
                val deliveryErrors = if (preview) emptyList() else Delivery.drain(outbox, fresh,
                    email = { report, queued -> Mailer.send(s, report, queued); s.status("lastSent", EventLog.stamp()) },
                    delivered = { EventLog.add(this, "Отчёт перемещён в архив ${it.name}") },
                    yieldArchive = { yieldArchive() })
                val deliveryFailed = deliveryErrors.isNotEmpty()
                for (e in deliveryErrors) {
                    val message = when (e) {
                        is javax.mail.AuthenticationFailedException -> "SMTP: проверьте логин и пароль приложения"
                        is javax.mail.MessagingException -> "SMTP: проверьте интернет, сервер, порт и TLS"
                        else -> "SMTP: отправка не выполнена (${e.javaClass.simpleName})"
                    }
                    s.status("problem", message); EventLog.add(this, message)
                    val failures = (s.prefs.getInt("failures", 0) + 1).coerceAtMost(6)
                    s.prefs.edit().putInt("failures", failures).apply()
                }
                if (!deliveryFailed && !captureFailed && s.pendingCapture == 0L && !preview) {
                    s.status("problem", "Нет")
                    s.prefs.edit().putInt("failures", 0).apply()
                }
                if (s.enabled && (outbox.count() > 0 || s.pendingCapture > 0)) {
                    val delay = when {
                        deliveryFailed || captureFailed ->
                            (5L shl (s.prefs.getInt("failures", 1).coerceIn(1, 6) - 1)).coerceAtMost(60) * 60_000
                        yieldArchive() -> 15 * 60_000L // The daily alarm will run first.
                        else -> 10_000L // Continue a large healthy queue without waiting for another daily photo.
                    }
                    Scheduler.retry(this, delay)
                }
                outbox.cleanup()
            } catch (e: Exception) {
                s.status("problem", "Ошибка выполнения: ${e.javaClass.simpleName}")
                EventLog.add(this, "Ошибка выполнения: ${e.javaClass.simpleName}")
                if (s.enabled) Scheduler.retry(this)
            } finally {
                if (lock.isHeld) lock.release()
                busy.set(false)
                if (runAgain.getAndSet(false) && s.enabled) main.post { if (instance === this && Settings(this).enabled) execute(false) }
                stopIfIdle()
            }
        }
    }
    override fun onDestroy() {
        if (instance === this) instance = null
        network?.stop()
        worker.shutdown()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
