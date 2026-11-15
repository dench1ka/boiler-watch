package ru.dacha.boiler

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
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "reports").deleteRecursively()
        s = Settings(context)
        s.prefs.edit().clear().putString("to", "owner@example.org").commit()
        outbox = Outbox(context)
    }
    private fun report() = outbox.save(byteArrayOf(1, 2, 3), s, 1)
    private fun savedAt(time: Long): File {
        val original = report()
        return File(original.parentFile, "$time-fixture").also { assertTrue(original.renameTo(it)) }
    }
    @Test fun successfulMailIsArchivedWithPhotoAndSlot() {
        val r = report()
        var sent = 0
        assertTrue(Delivery.deliver(outbox, r) { sent++ }.isEmpty())
        assertEquals(1, sent)
        assertEquals(0, outbox.count())
        assertTrue(outbox.hasSlot(1))
        assertNotNull(outbox.latest())
    }
    @Test fun failedMailRemainsQueuedAndCanRetryAfterRecreation() {
        val r = report()
        assertEquals(1, Delivery.deliver(outbox, r) { throw IOException() }.size)
        assertEquals(1, outbox.count())
        var sent = 0
        Delivery.deliver(Outbox(s.context), r) { sent++ }
        assertEquals(1, sent)
        assertEquals(0, outbox.count())
    }
    @Test fun oldMailReceiptPreventsResendingAfterUpgrade() {
        val r = report()
        outbox.updateMetadata(r, outbox.metadata(r).put("email", true).put("emailDone", true))
        Delivery.deliver(outbox, r) { fail("Already sent") }
        assertEquals(0, outbox.count())
        assertNotNull(outbox.latest())
    }
    @Test fun oldNonMailPhotoIsPreservedLocallyWithoutSendingToNewRecipient() {
        val r = report()
        outbox.updateMetadata(r, outbox.metadata(r).put("email", false))
        Delivery.deliver(outbox, r) { fail("This report never requested email") }
        assertEquals(0, outbox.count())
        assertNotNull(outbox.latest())
    }
    @Test fun receiptSurvivesFailedArchiveMoveWithoutSendingAgain() {
        val r = report()
        val target = File(s.context.filesDir, "reports/sent/${r.name}").apply { mkdirs() }
        File(target, "blocker").writeText("fixture")
        var sent = 0
        assertThrows(IllegalStateException::class.java) { Delivery.deliver(outbox, r) { sent++ } }
        assertTrue(outbox.metadata(r).getBoolean("emailDone"))
        target.deleteRecursively()
        Delivery.deliver(Outbox(s.context), r) { sent++ }
        assertEquals(1, sent)
        assertEquals(0, outbox.count())
    }
    @Test fun currentPhotoIsSentBeforeBacklog() {
        val old = savedAt(1); val fresh = savedAt(2)
        val events = mutableListOf<Pair<File, Boolean>>()
        Delivery.drain(outbox, fresh, { r, queued -> events.add(r to queued) })
        assertEquals(listOf(fresh to false, old to true), events)
        assertEquals(0, outbox.count())
    }
    @Test fun brokenOldReportCannotBlockTodaysPhoto() {
        File(s.context.filesDir, "reports/pending/broken").apply { mkdirs(); File(this, "report.json").writeText("corrupt") }
        val fresh = savedAt(2)
        val sent = mutableListOf<File>()
        val failures = Delivery.drain(outbox, fresh, { r, _ -> sent.add(r) })
        assertEquals(listOf(fresh), sent)
        assertEquals(1, failures.size)
        assertEquals(1, outbox.count())
    }
    @Test fun recoverySendsNewestFirstThenOlderPhotos() {
        val oldest = savedAt(1); val middle = savedAt(2); val newest = savedAt(3)
        val sent = mutableListOf<File>()
        Delivery.drain(outbox, null, { r, queued -> assertTrue(queued); sent.add(r) })
        assertEquals(listOf(newest, oldest, middle), sent)
        assertEquals(0, outbox.count())
    }
    @Test fun failingFreshMailStopsPassWithoutLosingAnyPhotos() {
        savedAt(1); val fresh = savedAt(2)
        var attempts = 0
        val failures = Delivery.drain(outbox, fresh, { _, _ -> attempts++; throw IOException() })
        assertEquals(1, failures.size)
        assertEquals(1, attempts)
        assertEquals(2, outbox.count())
    }
    @Test fun imminentTriggerDefersArchiveButStillSendsCurrentPhoto() {
        val old = savedAt(1); val fresh = savedAt(2)
        Delivery.drain(outbox, fresh, { r, _ -> assertEquals(fresh, r) }, yieldArchive = { true })
        assertEquals(listOf(old), outbox.queue())
        Delivery.drain(outbox, null, { _, _ -> fail("Capture is due first") }, yieldArchive = { true })
        assertEquals(listOf(old), outbox.queue())
    }
    @Test fun timeBudgetPreservesBacklogForNextPass() {
        savedAt(1); val fresh = savedAt(2)
        var time = 0L
        val sent = mutableListOf<File>()
        Delivery.drain(outbox, fresh, { r, _ -> sent.add(r) }, clock = { time.also { time += 45_000 } })
        assertEquals(listOf(fresh), sent)
        assertEquals(1, outbox.count())
        Delivery.drain(Outbox(s.context), null, { _, queued -> assertTrue(queued) })
        assertEquals(0, outbox.count())
    }
}
