package ru.dacha.boiler

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class TelegramException(val code: Int, val retryAfter: Int = 0) : IOException(
    when (code) {
        401 -> "Telegram: проверьте токен бота"
        403 -> "Telegram: откройте чат с ботом и разрешите ему отправку"
        409 -> "Telegram: бот уже используется другим приложением или webhook"
        429 -> "Telegram: слишком много запросов, повтор будет позже"
        else -> "Telegram временно недоступен ($code)"
    }
)

/** Production always uses HTTPS and refuses redirects so the token cannot leak to another host. */
class TelegramApi(private val token: String,
    private val connection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    @Volatile private var current: HttpURLConnection? = null
    companion object {
        fun validToken(value: String) = Regex("[0-9]{5,20}:[A-Za-z0-9_-]{20,100}").matches(value)
    }
    fun cancel() { current?.disconnect() }
    private fun open(method: String): HttpURLConnection {
        require(validToken(token)) { "Неверный формат токена бота" }
        return connection(URL("https://api.telegram.org/bot$token/$method")).apply {
            current = this
            instanceFollowRedirects = false; requestMethod = "POST"; doOutput = true
            connectTimeout = 15_000; readTimeout = 35_000
        }
    }
    private fun response(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val bytes = stream?.use { it.readBytesLimited(1_048_576) } ?: byteArrayOf()
        val data = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { throw TelegramException(code) }
        if (code !in 200..299 || !data.optBoolean("ok"))
            throw TelegramException(data.optInt("error_code", code), data.optJSONObject("parameters")?.optInt("retry_after", 0) ?: 0)
        return data
    }
    private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            if (out.size() + n > max) throw IOException("Telegram: слишком большой ответ")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
    internal fun request(method: String, payload: JSONObject): JSONObject {
        val c = open(method)
        try {
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            return response(c)
        } finally { c.disconnect(); if (current === c) current = null }
    }
    fun updates(offset: Long): JSONArray = request("getUpdates", JSONObject().put("offset", offset).put("timeout", 20)
        .put("limit", 25).put("allowed_updates", JSONArray().put("message"))).getJSONArray("result")
    fun message(chat: Long, text: String) {
        val keyboard = JSONArray().put(JSONArray().put("Статус").put("Фото сейчас"))
            .put(JSONArray().put("Пауза").put("Продолжить")).put(JSONArray().put("Расписание").put("Помощь"))
        request("sendMessage", JSONObject().put("chat_id", chat).put("text", text.take(3500))
            .put("reply_markup", JSONObject().put("keyboard", keyboard).put("resize_keyboard", true)))
    }
    fun photo(chat: Long, file: File, caption: String) {
        require(chat > 0 && file.isFile && file.length() in 1..10_000_000) { "Telegram: неверный чат или размер фото" }
        val c = open("sendPhoto")
        try {
            val boundary = "Boiler" + UUID.randomUUID().toString().replace("-", "")
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.setChunkedStreamingMode(16384)
            c.outputStream.use { out ->
                fun text(s: String) { out.write(s.toByteArray(Charsets.UTF_8)) }
                fun field(name: String, value: String) {
                    text("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                }
                field("chat_id", chat.toString()); field("caption", caption.take(1000))
                text("--$boundary\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"boiler.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
                file.inputStream().use { it.copyTo(out) }
                text("\r\n--$boundary--\r\n")
            }
            response(c)
        } finally { c.disconnect(); if (current === c) current = null }
    }
}
