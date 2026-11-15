package ru.dacha.boiler

import org.json.JSONObject

/** Always identify capture time, including when an old photo is delivered after an outage. */
object ReportText {
    fun title(report: JSONObject, queued: Boolean) =
        "Котёл: ${if (queued) "ИЗ ОЧЕРЕДИ — " else ""}фото от ${EventLog.stamp(report.getLong("captured"))}"

    fun body(report: JSONObject, queued: Boolean): String {
        val slot = report.getLong("slot")
        return (if (queued) "Из очереди: отложенная доставка, это не новый снимок.\n" else "") +
            "Фото снято: ${EventLog.stamp(report.getLong("captured"))}\n" +
            (if (slot > 0) "Плановое время: ${EventLog.stamp(slot)}\n" else "Ручная проверка\n") +
            "Отчёт: ${report.getString("id")}"
    }
}
