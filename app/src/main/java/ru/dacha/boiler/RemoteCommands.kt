package ru.dacha.boiler

import org.json.JSONObject

class RemoteCommands(private val settings: Settings, private val changed: () -> Unit, private val photo: () -> Unit) {
    companion object {
        const val HELP = "/status — состояние\n/photo — снять и отправить выбранными каналами\n/times 09:00, 18:30 — ежедневное расписание\n/pause — пауза фото\n/resume — продолжить фото\n/recipient name@example.org — получатель почты\n/flash on или off\n/camera back или front\n/rotation 0, 90, 180 или 270\n/alive on или off — ежедневный статус\n/alive_time 09:05 — время статуса\n/help — эта справка"
    }
    /** Private owner-only, bounded age, durable offset, no execution of edited/channel/bot messages. */
    fun process(update: JSONObject, now: Long = System.currentTimeMillis()): String? {
        val id = update.optLong("update_id", -1)
        if (id < settings.prefs.getLong("telegramOffset", 0) || id < 0) return null
        check(settings.prefs.edit().putLong("telegramOffset", id + 1).commit()) { "Не удалось сохранить номер команды" }
        val message = update.optJSONObject("message") ?: return null
        val chat = message.optJSONObject("chat") ?: return null
        val from = message.optJSONObject("from") ?: return null
        val user = from.optLong("id")
        if (chat.optString("type") != "private" || user <= 0 || chat.optLong("id") != user || from.optBoolean("is_bot")) return null
        val age = now / 1000 - message.optLong("date")
        val raw = message.optString("text").trim()
        val text = mapOf("Статус" to "/status", "Фото сейчас" to "/photo", "Пауза" to "/pause",
            "Продолжить" to "/resume", "Расписание" to "/times", "Помощь" to "/help")[raw] ?: raw
        if (settings.pairing) {
            if (age !in -60..300 || text != "/pair ${settings.prefs.getString("pairCode", "")}") return null
            check(settings.prefs.edit().putLong("telegramChat", user).putLong("telegramUser", user)
                .remove("pairCode").remove("pairUntil").commit())
            changed()
            return "Телефон привязан. В приложении сохраните нужные функции и включите наблюдение.\n$HELP"
        }
        if (!settings.enabled || !settings.remoteControl || user != settings.telegramUser || chat.optLong("id") != settings.telegramChat) return null
        if (age !in -60..300) return "Команда устарела. Отправьте её ещё раз"
        return try {
            val command = text.substringBefore(' ').substringBefore('@').lowercase(java.util.Locale.ROOT)
            val value = text.substringAfter(' ', "").trim()
            when (command) {
                "/start", "/help" -> HELP
                "/status" -> status()
                "/photo" -> {
                    require(settings.hasPhotoRoute) { "Сначала выберите канал отправки фото в приложении" }
                    check(settings.prefs.edit().putLong("remotePhoto", id).commit())
                    photo(); "Задание на фото принято. Отправлю выбранными в приложении каналами"
                }
                "/times" -> {
                    if (value.isEmpty()) "Ежедневно: ${settings.times}\nДля изменения отправьте, например: /times 09:00, 18:30"
                    else { Schedule.parse(value); save("times", value); changed(); "Расписание сохранено: $value (время телефона)" }
                }
                "/pause" -> { save("paused", true); settings.pendingCapture = 0; changed(); "Съёмка приостановлена. Связь и сообщения о работе остаются включены" }
                "/resume" -> { save("paused", false); changed(); "Съёмка возобновлена" }
                "/recipient" -> { Mailer.validateAddress(value); save("to", value); "Получатель сохранён. Уже снятые отчёты сохраняют прежний адрес" }
                "/flash" -> { save("flash", onOff(value)); "Вспышка: $value" }
                "/camera" -> { require(value in listOf("back", "front")) { "Используйте /camera back или /camera front" }; save("front", value == "front"); "Камера: $value" }
                "/rotation" -> { val n = value.toIntOrNull(); require(n in listOf(0, 90, 180, 270)) { "Допустимо: 0, 90, 180, 270" }; check(settings.prefs.edit().putInt("rotation", n!!).commit()); "Поворот: $n°" }
                "/alive" -> { save("heartbeat", onOff(value)); settings.prefs.edit().putLong("heartbeatNext", 0).commit(); changed(); "Сообщения о работе: $value" }
                "/alive_time" -> { require(Schedule.parse(value).size == 1) { "Укажите одно время HH:mm" }; save("heartbeatTime", value); settings.prefs.edit().putLong("heartbeatNext", Schedule.next(value, now)).commit(); changed(); "Время сообщения: $value" }
                else -> "Неизвестная команда.\n$HELP"
            }
        } catch (_: Exception) { "Не удалось изменить настройку. Проверьте формат команды.\n$HELP" }
    }
    private fun onOff(value: String): Boolean {
        require(value == "on" || value == "off") { "Используйте on или off" }; return value == "on"
    }
    private fun save(key: String, value: String) { check(settings.prefs.edit().putString(key, value).commit()) }
    private fun save(key: String, value: Boolean) { check(settings.prefs.edit().putBoolean(key, value).commit()) }
    fun status(): String = "Телефон работает: ${EventLog.stamp()}\n" +
        "Наблюдение: ${if (settings.enabled) "включено" else "выключено"}\n" +
        "Фото: ${if (!settings.hasPhotoRoute) "отключены" else if (settings.paused) "пауза" else "по расписанию"}\nЕжедневно: ${settings.times}\n" +
        "Последнее фото: ${settings.text("lastPhoto")}\nПочта: ${settings.text("lastSent")}\n" +
        "Telegram: ${settings.text("lastTelegramPhoto")}\nОчередь: ${Outbox(settings.context).count()}\n" +
        "Ошибка фото/доставки: ${settings.text("problem")}"
}
