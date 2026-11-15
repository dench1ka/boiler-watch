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
}
