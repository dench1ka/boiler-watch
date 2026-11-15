package ru.dacha.boiler

import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.Properties
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/** Immutable snapshot prevents SMTP settings from changing midway through a delivery. */
class SmtpConnection(
    val host: String, val port: Int, val user: String, val password: String,
    val from: String, val ssl: Boolean
) {
    override fun toString() = "SmtpConnection(credentials redacted)"
}

object Mailer {
    fun validateAddress(value: String) {
        require(value.none { it == '\r' || it == '\n' }) { "Адрес не должен содержать переносов строк" }
        val addresses = InternetAddress.parse(value, true)
        require(addresses.size == 1) { "Укажите один адрес почты" }
        require(!addresses[0].isGroup) { "Укажите адрес получателя, а не почтовую группу" }
        addresses[0].validate()
        require(addresses[0].address.contains("@")) { "Укажите полный адрес почты" }
    }
    fun send(s: Settings, dir: File) = send(
        SmtpConnection(s.host, s.port, s.user, s.password(), s.from, s.ssl), dir)

    fun send(s: SmtpConnection, dir: File) {
        val props = Properties().apply {
            setProperty("mail.smtp.auth", "true")
            setProperty("mail.smtp.connectiontimeout", "20000")
            setProperty("mail.smtp.timeout", "20000")
            setProperty("mail.smtp.writetimeout", "20000")
            setProperty("mail.smtp.ssl.checkserveridentity", "true")
            setProperty("mail.smtp.ssl.protocols", "TLSv1.2")
            if (s.ssl) setProperty("mail.smtp.ssl.enable", "true")
            else {
                setProperty("mail.smtp.starttls.enable", "true")
                setProperty("mail.smtp.starttls.required", "true")
            }
        }
        val session = Session.getInstance(props)
        val message = compose(session, s.from, dir)
        val transport = session.getTransport("smtp")
        try {
            transport.connect(s.host, s.port, s.user, s.password)
            transport.sendMessage(message, message.allRecipients)
        } finally { try { transport.close() } catch (_: Exception) { } }
    }
    internal fun compose(session: Session, from: String, dir: File): MimeMessage {
        val report = JSONObject(File(dir, "report.json").readText())
        validateAddress(from)
        validateAddress(report.getString("recipient"))
        val photoFile = File(dir, "photo.jpg")
        require(photoFile.isFile && photoFile.length() > 0) { "Файл снимка отсутствует или пуст" }
        return MimeMessage(session).apply {
            setFrom(InternetAddress(from))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(report.getString("recipient"), true))
            setSubject("Котёл: фото от ${EventLog.stamp(report.getLong("captured"))}", "UTF-8")
            sentDate = Date()
            val scheduled = report.getLong("slot")
            val body = MimeBodyPart().apply {
                setText("Фото снято: ${EventLog.stamp(report.getLong("captured"))}\n" +
                    (if (scheduled > 0) "Плановое время: ${EventLog.stamp(scheduled)}\n" else "Ручная проверка\n") +
                    "Отчёт: ${report.getString("id")}\nСнимок приложен. Приложение не оценивает состояние котла автоматически.", "UTF-8")
            }
            val photo = MimeBodyPart().apply {
                attachFile(photoFile); fileName = "boiler-${report.getLong("captured")}.jpg"
            }
            setContent(MimeMultipart().apply { addBodyPart(body); addBodyPart(photo) })
        }
    }
}
