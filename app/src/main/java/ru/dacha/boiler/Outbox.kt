package ru.dacha.boiler

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

class Outbox(private val context: Context) {
    private val root = File(context.filesDir, "reports").apply { check(isDirectory || mkdirs()) { "Не удалось открыть каталог архива" } }
    private val pending = File(root, "pending").apply { check(isDirectory || mkdirs()) { "Не удалось открыть каталог архива" } }
    private val sent = File(root, "sent").apply { check(isDirectory || mkdirs()) { "Не удалось открыть каталог архива" } }
    fun queue(): List<File> = pending.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name }
    fun count() = queue().size
    fun cleanup() {
        val limit = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
        sent.listFiles().orEmpty().filter { it.lastModified() < limit }.forEach { it.deleteRecursively() }
        // A crash during atomic staging must not leave partial reports indefinitely.
        pending.listFiles().orEmpty().filter { it.name.startsWith(".") && it.lastModified() < System.currentTimeMillis() - 3600_000L }
            .forEach { it.deleteRecursively() }
    }
    fun checkSpace() {
        cleanup()
        require(root.walkTopDown().filter { it.isFile }.sumOf { it.length() } < 100L * 1024 * 1024) {
            "Архив достиг 100 МБ. Освободите место или восстановите отправку; неотправленные фото сохранены"
        }
        require(root.usableSpace > 20L * 1024 * 1024) { "На телефоне осталось меньше 20 МБ" }
    }
    fun hasSlot(slot: Long) = (queue() + sent.listFiles().orEmpty().toList()).any {
        try { JSONObject(File(it, "report.json").readText()).optLong("slot") == slot } catch (_: Exception) { false }
    }
    fun save(bytes: ByteArray, settings: Settings, slot: Long): File {
        require(bytes.isNotEmpty()) { "Нельзя сохранить пустой снимок" }
        val now = System.currentTimeMillis()
        val id = "$now-${UUID.randomUUID()}"
        val staging = File(pending, ".$id").apply { check(isDirectory || mkdirs()) { "Не удалось открыть каталог архива" } }
        val report = JSONObject().put("captured", now).put("slot", slot)
            .put("recipient", settings.to).put("id", id)
        try {
            File(staging, "photo.jpg").outputStream().use { it.write(bytes); it.fd.sync() }
            File(staging, "report.json").outputStream().use { it.write(report.toString().toByteArray()); it.fd.sync() }
            val target = File(pending, id)
            check(staging.renameTo(target)) { "Не удалось сохранить отчёт" }
            return target
        } finally {
            // After a successful rename this path no longer exists; failed saves leave no partial data.
            if (staging.exists()) staging.deleteRecursively()
        }
    }
    fun metadata(report: File) = JSONObject(File(report, "report.json").readText())
    fun updateMetadata(report: File, data: JSONObject) {
        val temporary = File(report, "report.json.tmp")
        temporary.outputStream().use { it.write(data.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        check(temporary.renameTo(File(report, "report.json"))) { "Не удалось сохранить подтверждение доставки" }
    }
    fun delivered(report: File) {
        check(report.renameTo(File(sent, report.name))) { "SMTP принял письмо, но архив не обновлён: возможна повторная отправка" }
        File(sent, report.name).setLastModified(System.currentTimeMillis())
    }
    fun latest(): File? = ((queue() + sent.listFiles().orEmpty().filter { it.isDirectory })
        .map { File(it, "photo.jpg") } + File(context.filesDir, "preview.jpg"))
        .filter { it.exists() }.maxByOrNull { it.lastModified() }
}
