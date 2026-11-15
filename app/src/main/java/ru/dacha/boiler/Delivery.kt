package ru.dacha.boiler

import org.json.JSONObject
import java.io.File

/** Per-channel receipts survive process death; a failed channel cannot block the other. */
object Delivery {
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
