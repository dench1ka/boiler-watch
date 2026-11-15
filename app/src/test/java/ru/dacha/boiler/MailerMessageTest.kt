package ru.dacha.boiler

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 35])
class MailerMessageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val photo = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3, 0xff.toByte(), 0xd9.toByte())
    private fun report(slot: Long): File = temporary.newFolder().apply {
        File(this, "photo.jpg").writeBytes(photo)
        File(this, "report.json").writeText(JSONObject().put("captured", 1794700000000L)
            .put("slot", slot).put("recipient", "original@example.org").put("id", "test-report-id").toString())
    }
    @Test fun completeMimeRoundTripPreservesAttachmentAndRecipient() {
        val session = Session.getInstance(Properties())
        val original = Mailer.compose(session, "sender@example.org", report(1794600000000L))
        val output = ByteArrayOutputStream()
        original.writeTo(output)
        val parsed = MimeMessage(session, ByteArrayInputStream(output.toByteArray()))
        assertEquals("original@example.org", parsed.getRecipients(Message.RecipientType.TO).single().toString())
        assertEquals("sender@example.org", parsed.from.single().toString())
        assertTrue(parsed.subject.startsWith("Котёл:"))
        val parts = parsed.content as MimeMultipart
        assertEquals(2, parts.count)
        assertTrue(parts.getBodyPart(0).content.toString().contains("test-report-id"))
        assertTrue(parts.getBodyPart(0).content.toString().contains("Плановое время:"))
        assertArrayEquals(photo, parts.getBodyPart(1).inputStream.use { it.readBytes() })
        assertTrue(parts.getBodyPart(1).fileName.endsWith(".jpg"))
    }
    @Test fun manualReportDoesNotClaimScheduledCapture() {
        val message = Mailer.compose(Session.getInstance(Properties()), "sender@example.org", report(0))
        val text = (message.content as MimeMultipart).getBodyPart(0).content.toString()
        assertTrue(text.contains("Ручная проверка"))
        assertFalse(text.contains("Плановое время:"))
    }
    @Test fun missingPhotoIsRejectedBeforeConnectingToSmtp() {
        val dir = report(0)
        File(dir, "photo.jpg").delete()
        assertThrows(Exception::class.java) { Mailer.compose(Session.getInstance(Properties()), "sender@example.org", dir) }
    }
    @Test fun delayedPhotoIsMarkedInSubjectAndBodyWithOriginalCaptureTime() {
        val dir = report(1794600000000L)
        val session = Session.getInstance(Properties())
        val message = Mailer.compose(session, "sender@example.org", dir, queued = true)
        val output = ByteArrayOutputStream().also { message.writeTo(it) }
        val parsed = MimeMessage(session, ByteArrayInputStream(output.toByteArray()))
        assertTrue(parsed.subject.contains("ИЗ ОЧЕРЕДИ"))
        assertTrue(parsed.subject.contains(EventLog.stamp(1794700000000L)))
        val text = (parsed.content as MimeMultipart).getBodyPart(0).content.toString()
        assertTrue(text.contains("это не новый снимок"))
        assertTrue(text.contains("Фото снято: ${EventLog.stamp(1794700000000L)}"))
        val fresh = Mailer.compose(session, "sender@example.org", dir)
        assertFalse(fresh.subject.contains("ИЗ ОЧЕРЕДИ"))
    }
    @Test fun reportBodyDistinguishesDelayedPhotoAndPreservesSchedule() {
        val report = JSONObject(File(report(1794600000000L), "report.json").readText())
        val caption = ReportText.body(report, queued = true)
        assertTrue(caption.startsWith("Из очереди:"))
        assertTrue(caption.contains(EventLog.stamp(report.getLong("captured"))))
        assertTrue(caption.contains("Плановое время: ${EventLog.stamp(report.getLong("slot"))}"))
        assertFalse(ReportText.body(report, queued = false).contains("Из очереди"))
    }
}
