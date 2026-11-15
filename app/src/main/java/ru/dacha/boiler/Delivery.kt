package ru.dacha.boiler

import org.json.JSONObject
import java.io.File
import android.os.SystemClock

/** Per-channel receipts survive process death; a failed channel cannot block the other. */
object Delivery {
    /** Try the current photo in BOTH channels before spending time on the archive.
     * On recovery without a new capture, the newest queued photo has the same priority.
     * Each channel stops independently on failure; receipts still survive process death.
     */
    fun drain(outbox: Outbox, fresh: File?, email: (File, Boolean) -> Unit,
        telegram: (File, Boolean) -> Unit, delivered: (File) -> Unit = {},
        yieldArchive: () -> Boolean = { false }, clock: () -> Long = { SystemClock.elapsedRealtime() }
    ): List<Pair<String, Exception>> {
        val queued = outbox.queue()
        val priority = fresh?.takeIf { it in queued } ?: queued.maxByOrNull {
            it.name.substringBefore('-').toLongOrNull() ?: Long.MIN_VALUE
        }
        val failures = mutableListOf<Pair<String, Exception>>()
        val blocked = mutableSetOf<String>()
        fun attempt(report: File, channel: String) {
            if (!report.exists()) return
            try {
                val errors = deliver(outbox, report,
                    { email(report, report != fresh) }, { telegram(report, report != fresh) }, channel)
                if (errors.isNotEmpty()) throw errors.first()
                if (!report.exists()) delivered(report)
            } catch (e: Exception) {
                failures.add(channel to e)
                blocked.add(channel)
            }
        }
        if (priority != null && (priority == fresh || !yieldArchive()))
            for (channel in listOf("email", "telegram")) attempt(priority, channel)
        for (channel in listOf("email", "telegram")) {
            val deadline = clock() + 45_000L
            for (report in queued) {
                if (channel in blocked || clock() >= deadline || yieldArchive()) break
                if (report != priority) attempt(report, channel)
            }
        }
        return failures
    }

    fun deliver(outbox: Outbox, report: File, email: () -> Unit, telegram: () -> Unit, channel: String? = null): List<Exception> {
        val metadata = outbox.metadata(report)
        val failures = mutableListOf<Exception>()
        for ((required, done, sender) in listOf(
            Triple(metadata.optBoolean("email", true), "emailDone", email),
            Triple(metadata.optBoolean("telegram", false), "telegramDone", telegram))) {
            if (required && !metadata.optBoolean(done) && (channel == null || done == channel + "Done")) {
                try {
                    sender()
                    metadata.put(done, true)
                    outbox.updateMetadata(report, metadata)
                } catch (e: Exception) { failures.add(e) }
            }
        }
        if ((!metadata.optBoolean("email", true) || metadata.optBoolean("emailDone")) &&
            (!metadata.optBoolean("telegram", false) || metadata.optBoolean("telegramDone"))) outbox.delivered(report)
        return failures
    }
}
