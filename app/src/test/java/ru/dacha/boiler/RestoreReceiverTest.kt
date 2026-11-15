package ru.dacha.boiler

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 35])
class RestoreReceiverTest {
    private lateinit var context: Context
    private lateinit var settings: Settings
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        settings = Settings(context)
        settings.prefs.edit().clear().commit()
        Scheduler.cancel(context)
    }
    private fun boot() = RestoreReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
    @Test fun stoppedMonitoringDoesNotStartAfterBoot() {
        boot()
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }
    @Test fun enabledMonitoringRestoresAlarmAndUsesPermittedStartupMode() {
        settings.enabled = true
        val expected = System.currentTimeMillis() + 3_600_000
        settings.next = expected
        boot()
        assertEquals(expected, settings.next)
        assertEquals(1, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        if (Build.VERSION.SDK_INT < 28) assertEquals(MonitorService::class.java.name, started!!.component!!.className)
        else { assertNull(started); assertTrue(settings.text("problem").contains("Откройте приложение")) }
    }
    @Test fun missedCaptureIsRecoveredOnceAtBoot() {
        settings.enabled = true
        val missed = System.currentTimeMillis() - 60_000
        settings.next = missed
        boot()
        assertEquals(missed, settings.pendingCapture)
        assertTrue(settings.next > System.currentTimeMillis())
        boot()
        assertEquals(missed, settings.pendingCapture)
    }
    @Test fun existingPendingCaptureIsNotOverwrittenByLaterMissedTime() {
        settings.enabled = true
        settings.pendingCapture = 123
        settings.next = System.currentTimeMillis() - 60_000
        boot()
        assertEquals(123L, settings.pendingCapture)
    }
    @Test fun clockChangeRecomputesFutureSchedule() {
        settings.enabled = true
        settings.next = Long.MAX_VALUE
        RestoreReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        assertTrue(settings.next > System.currentTimeMillis())
        assertTrue(settings.next < Long.MAX_VALUE)
        assertEquals(0L, settings.pendingCapture)
    }
}
