package ru.dacha.boiler

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 35])
class DeliveryTest {
    private lateinit var s: Settings
    private lateinit var outbox: Outbox
    private var email = 0
    private var telegram = 0
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "reports").deleteRecursively()
        s = Settings(context)
        s.prefs.edit().clear().putBoolean("emailPhotos", true).putBoolean("telegramPhotos", true)
            .putLong("telegramChat", 123).putString("telegramBot", "123456").putString("to", "owner@example.org").commit()
        outbox = Outbox(context); email = 0; telegram = 0
    }
    private fun report() = outbox.save(byteArrayOf(1,2,3), s, 1, 42)
    @Test fun bothChannelsDeliverAndArchive() {
        val r = report()
        assertTrue(Delivery.deliver(outbox, r, {email++}, {telegram++}).isEmpty())
        assertEquals(1,email);assertEquals(1,telegram);assertEquals(0,outbox.count())
        assertTrue(outbox.hasSlot(1));assertTrue(outbox.hasRemoteRequest(42))
    }
    @Test fun telegramFailureNeverResendsAcceptedEmail() {
        val r = report()
        assertEquals(1,Delivery.deliver(outbox,r,{email++},{throw IOException()}).size)
        assertTrue(outbox.metadata(r).getBoolean("emailDone"));assertEquals(1,outbox.count())
        Delivery.deliver(Outbox(s.context),r,{email++},{telegram++})
        assertEquals(1,email);assertEquals(1,telegram);assertEquals(0,outbox.count())
    }
    @Test fun emailFailureDoesNotBlockTelegram() {
        val r=report()
        Delivery.deliver(outbox,r,{throw IOException()},{telegram++})
        assertEquals(1,telegram);assertTrue(outbox.metadata(r).getBoolean("telegramDone"))
        Delivery.deliver(outbox,r,{email++},{telegram++})
        assertEquals(1,email);assertEquals(1,telegram);assertEquals(0,outbox.count())
    }
    @Test fun telegramOnlyDoesNotCallSmtp() {
        s.prefs.edit().putBoolean("emailPhotos",false).commit()
        Delivery.deliver(outbox,report(),{fail("SMTP must be off")},{telegram++})
        assertEquals(1,telegram);assertEquals(0,outbox.count())
    }
    @Test fun emailOnlyDoesNotCallTelegram() {
        s.prefs.edit().putBoolean("telegramPhotos",false).commit()
        Delivery.deliver(outbox,report(),{email++},{fail("Telegram must be off")})
        assertEquals(1,email);assertEquals(0,outbox.count())
    }
    @Test fun channelSelectionCannotChangeExistingReport() {
        val r=report()
        s.prefs.edit().putBoolean("emailPhotos",false).putBoolean("telegramPhotos",false).putLong("telegramChat",999).commit()
        assertEquals(123L,outbox.metadata(r).getLong("telegramChat"))
        Delivery.deliver(outbox,r,{email++},{telegram++})
        assertEquals(1,email);assertEquals(1,telegram)
    }
    @Test fun legacyReportStillUsesOnlyEmail() {
        val r=report(); val metadata=outbox.metadata(r)
        metadata.remove("email");metadata.remove("telegram");outbox.updateMetadata(r,metadata)
        Delivery.deliver(outbox,r,{email++},{fail("Legacy reports only used SMTP")})
        assertEquals(1,email)
    }
    @Test fun perChannelPassPreservesOtherChannelReceipt() {
        val r=report()
        Delivery.deliver(outbox,r,{email++},{fail("Wrong phase")},channel="email")
        assertEquals(1,outbox.count());assertTrue(outbox.metadata(r).getBoolean("emailDone"))
        Delivery.deliver(outbox,r,{fail("Wrong phase")},{telegram++},channel="telegram")
        assertEquals(0,outbox.count());assertEquals(1,telegram)
    }
    private fun savedAt(time: Long): File {
        val original = report()
        return File(original.parentFile, "$time-fixture").also { assertTrue(original.renameTo(it)) }
    }
    @Test fun currentPhotoReachesBothChannelsBeforeAnyBacklog() {
        val yesterday = savedAt(1)
        val today = savedAt(2)
        val events = mutableListOf<String>()
        assertTrue(Delivery.drain(outbox, today,
            { r, queued -> events.add("email:${r.name}:$queued") },
            { r, queued -> events.add("telegram:${r.name}:$queued") }).isEmpty())
        assertEquals(listOf("email:${today.name}:false", "telegram:${today.name}:false",
            "email:${yesterday.name}:true", "telegram:${yesterday.name}:true"), events)
        assertEquals(0, outbox.count())
    }
    @Test fun brokenOldReportCannotBlockTodaysPhoto() {
        File(s.context.filesDir, "reports/pending/broken").apply { mkdirs(); File(this, "report.json").writeText("corrupt") }
        val today = savedAt(2)
        val sent = mutableListOf<File>()
        val failures = Delivery.drain(outbox, today, { r, _ -> sent.add(r) }, { r, _ -> sent.add(r) })
        assertEquals(listOf(today, today), sent)
        assertEquals(2, failures.size)
        assertEquals(1, outbox.count())
    }
    @Test fun recoverySendsNewestFirstThenDrainsAllOlderPhotos() {
        val oldest = savedAt(1); val middle = savedAt(2); val newest = savedAt(3)
        val mail = mutableListOf<File>(); val tg = mutableListOf<File>()
        Delivery.drain(outbox, null,
            { r, queued -> assertTrue(queued); mail.add(r) },
            { r, queued -> assertTrue(queued); tg.add(r) })
        assertEquals(listOf(newest, oldest, middle), mail)
        assertEquals(mail, tg)
        assertEquals(0, outbox.count())
    }
    @Test fun failingOldSmtpCannotDelayFreshTelegramOrItsBacklog() {
        val old = savedAt(1); val fresh = savedAt(2)
        val events = mutableListOf<String>()
        val errors = Delivery.drain(outbox, fresh,
            { r, _ -> events.add("email:${r.name}"); if (r == old) throw IOException() },
            { r, _ -> events.add("telegram:${r.name}") })
        assertEquals(listOf("email:${fresh.name}", "telegram:${fresh.name}", "email:${old.name}", "telegram:${old.name}"), events)
        assertEquals(listOf("email"), errors.map { it.first })
        assertTrue(outbox.metadata(old).getBoolean("telegramDone"))
        Delivery.drain(outbox, null, { _, _ -> email++ }, { _, _ -> fail("Already delivered to Telegram") })
        assertEquals(0, outbox.count())
    }
    @Test fun failingFreshChannelStillAllowsOtherChannelToDrain() {
        val old = savedAt(1); val fresh = savedAt(2)
        val tg = mutableListOf<File>()
        val errors = Delivery.drain(outbox, fresh, { _, _ -> throw IOException() }, { r, _ -> tg.add(r) })
        assertEquals(listOf(fresh, old), tg)
        assertEquals(listOf("email"), errors.map { it.first })
        assertEquals(2, outbox.count())
    }
    @Test fun imminentTriggerDefersArchiveButStillSendsCurrentPhoto() {
        val old = savedAt(1); val fresh = savedAt(2)
        Delivery.drain(outbox, fresh, { r, _ -> assertEquals(fresh, r) },
            { r, _ -> assertEquals(fresh, r) }, yieldArchive = { true })
        assertEquals(listOf(old), outbox.queue())
        Delivery.drain(outbox, null, { _, _ -> fail("Capture is due first") },
            { _, _ -> fail("Capture is due first") }, yieldArchive = { true })
        assertEquals(listOf(old), outbox.queue())
    }
    @Test fun backlogBudgetsAreIndependentAndPreserveUnsentPhotosForNextPass() {
        savedAt(1); val fresh = savedAt(2)
        var time = 0L
        val emails = mutableListOf<File>(); val tg = mutableListOf<File>()
        Delivery.drain(outbox, fresh, { r, _ -> emails.add(r) },
            { r, _ -> tg.add(r) }, clock = { time.also { time += 45_000 } })
        assertEquals(listOf(fresh), emails); assertEquals(listOf(fresh), tg)
        assertEquals(1, outbox.count())
        Delivery.drain(Outbox(s.context), null, { _, queued -> assertTrue(queued) },
            { _, queued -> assertTrue(queued) })
        assertEquals(0, outbox.count())
    }
}
