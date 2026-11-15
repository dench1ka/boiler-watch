package ru.dacha.boiler

import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 35])
class OutboxTest {
    private lateinit var context: Context
    private lateinit var settings: Settings
    private lateinit var outbox: Outbox
    private val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3)
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "reports").deleteRecursively()
        File(context.filesDir, "preview.jpg").delete()
        settings = Settings(context)
        settings.prefs.edit().clear().putString("to", "owner@example.org").commit()
        outbox = Outbox(context)
    }
    @After fun cleanup() { File(context.filesDir, "reports").deleteRecursively(); File(context.filesDir, "preview.jpg").delete() }
    @Test fun savedReportContainsOriginalImageAndRecipient() {
        val file = outbox.save(bytes, settings, 123)
        assertArrayEquals(bytes, File(file, "photo.jpg").readBytes())
        val metadata = JSONObject(File(file, "report.json").readText())
        assertEquals("owner@example.org", metadata.getString("recipient"))
        assertEquals(123L, metadata.getLong("slot"))
        assertTrue(metadata.getLong("captured") > 0)
        assertEquals(listOf(file), outbox.queue())
    }
    @Test fun recipientIsNotChangedForExistingReports() {
        val file = outbox.save(bytes, settings, 123)
        settings.prefs.edit().putString("to", "new@example.org").commit()
        assertEquals("owner@example.org", JSONObject(File(file, "report.json").readText()).getString("recipient"))
    }
    @Test fun deliveryMovesReportOutOfQueueButPreservesSlotAndImage() {
        val file = outbox.save(bytes, settings, 123)
        outbox.delivered(file)
        assertEquals(0, outbox.count())
        assertTrue(outbox.hasSlot(123))
        assertArrayEquals(bytes, outbox.latest()!!.readBytes())
    }
    @Test fun pendingReportsAreNeverDeletedByRetention() {
        val file = outbox.save(bytes, settings, 123)
        file.setLastModified(1)
        outbox.cleanup()
        assertTrue(file.exists())
        assertEquals(1, outbox.count())
    }
    @Test fun sentReportsExpireAfterSevenDays() {
        val file = outbox.save(bytes, settings, 123)
        outbox.delivered(file)
        File(context.filesDir, "reports/sent/${file.name}").setLastModified(1)
        outbox.cleanup()
        assertNull(outbox.latest())
    }
    @Test fun incompleteReportsAreHiddenAndEventuallyCleaned() {
        val file = File(context.filesDir, "reports/pending/.interrupted").apply { mkdirs(); setLastModified(1) }
        assertEquals(0, outbox.count())
        outbox.cleanup()
        assertFalse(file.exists())
    }
    @Test fun recentIncompleteReportIsNotRemovedByCleanup() {
        val file = File(context.filesDir, "reports/pending/.working").apply { mkdirs() }
        outbox.cleanup()
        assertTrue(file.exists())
    }
    @Test fun repeatedCapturesHaveDistinctIds() {
        val first = outbox.save(bytes, settings, 0)
        val second = outbox.save(bytes, settings, 0)
        assertNotEquals(first.name, second.name)
        assertEquals(2, outbox.count())
    }
    @Test fun corruptMetadataCannotEraseOtherSlots() {
        File(context.filesDir, "reports/pending/broken").apply { mkdirs(); File(this, "report.json").writeText("broken") }
        outbox.save(bytes, settings, 123)
        assertTrue(outbox.hasSlot(123))
        assertFalse(outbox.hasSlot(456))
    }
    @Test fun previewNeverEntersSmtpQueue() {
        val preview = File(context.filesDir, "preview.jpg").apply { writeBytes(bytes) }
        assertEquals(0, outbox.count())
        assertEquals(preview, outbox.latest())
    }
    @Test fun emptyPhotoIsRejectedWithoutCreatingQueueEntry() {
        assertThrows(IllegalArgumentException::class.java) { outbox.save(byteArrayOf(), settings, 0) }
        assertEquals(0, outbox.count())
        assertTrue(File(context.filesDir, "reports/pending").listFiles().orEmpty().isEmpty())
    }
    @Test fun unavailableStorageIsReportedAtInitialization() {
        File(context.filesDir, "reports").deleteRecursively()
        File(context.filesDir, "reports").writeText("not a directory")
        assertThrows(IllegalStateException::class.java) { Outbox(context) }
    }
}
