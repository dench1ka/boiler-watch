package ru.dacha.boiler

import android.content.Context
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
class EventLogTest {
    private lateinit var context: Context
    private lateinit var file: File
    @Before fun setup() { context = RuntimeEnvironment.getApplication(); file = File(context.filesDir, "events.txt"); file.deleteRecursively() }
    @After fun cleanup() { file.deleteRecursively() }
    @Test fun writesEventsInOrder() {
        EventLog.add(context, "first")
        EventLog.add(context, "second")
        val text = EventLog.read(context)
        assertTrue(text.indexOf("first") < text.indexOf("second"))
    }
    @Test fun unavailableLogCannotBreakAlarmOrDelivery() {
        file.mkdirs()
        EventLog.add(context, "must not crash")
        assertTrue(EventLog.read(context).isNotBlank())
    }
    @Test fun missingLogIsReadable() { assertTrue(EventLog.read(context).isNotBlank()) }
}
