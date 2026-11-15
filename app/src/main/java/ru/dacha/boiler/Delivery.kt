package ru.dacha.boiler

import android.os.SystemClock
import java.io.File

/** Send the current photo first, then spend a bounded amount of time on older mail. */
object Delivery {
    fun drain(outbox: Outbox, fresh: File?, email: (File, Boolean) -> Unit,
        delivered: (File) -> Unit = {}, yieldArchive: () -> Boolean = { false },
        clock: () -> Long = { SystemClock.elapsedRealtime() }
    ): List<Exception> {
        val queued = outbox.queue()
        val priority = fresh?.takeIf { it in queued } ?: queued.maxByOrNull {
            it.name.substringBefore('-').toLongOrNull() ?: Long.MIN_VALUE
        }
        val failures = mutableListOf<Exception>()
        fun attempt(report: File) {
            if (!report.exists()) return
            try {
                val errors = deliver(outbox, report) { email(report, report != fresh) }
                if (errors.isNotEmpty()) throw errors.first()
                delivered(report)
            } catch (e: Exception) { failures.add(e) }
        }
        if (priority != null && (priority == fresh || !yieldArchive())) attempt(priority)
        val deadline = clock() + 45_000L
        for (report in queued) {
            if (failures.isNotEmpty() || clock() >= deadline || yieldArchive()) break
            if (report != priority) attempt(report)
        }
        return failures
    }

    fun deliver(outbox: Outbox, report: File, email: () -> Unit): List<Exception> {
        val metadata = outbox.metadata(report)
        // Older reports may already have a mail receipt, or may never have requested mail.
        // Archive these locally, preserving the photo and the original recipient.
        if (metadata.optBoolean("email", true) && !metadata.optBoolean("emailDone")) {
            try {
                email()
                metadata.put("emailDone", true)
                outbox.updateMetadata(report, metadata)
            } catch (e: Exception) { return listOf(e) }
        }
        outbox.delivered(report)
        return emptyList()
    }
}
