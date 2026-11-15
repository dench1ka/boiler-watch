package ru.dacha.boiler

import android.app.AlarmManager
import android.content.Context
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
class SchedulerTest {
    private lateinit var context: Context
    private lateinit var settings: Settings
    private lateinit var alarms: AlarmManager
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        settings = Settings(context)
        settings.prefs.edit().clear().commit()
        alarms = context.getSystemService(AlarmManager::class.java)
        Scheduler.cancel(context)
    }
    @Test fun pauseKeepsTelegramWakeupButRemovesCameraAlarm() {
        settings.enabled = true
        settings.prefs.edit().putBoolean("remoteControl", true).putBoolean("paused", true).commit()
        Scheduler.plan(context)
        assertEquals(0L, settings.next)
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
        settings.prefs.edit().putBoolean("paused", false).commit()
        Scheduler.plan(context)
        assertEquals(2, shadowOf(alarms).scheduledAlarms.size)
    }
    @Test fun statusOnlyModeDoesNotSchedulePhotos() {
        settings.enabled = true
        settings.prefs.edit().putBoolean("emailPhotos", false).putBoolean("heartbeat", true).commit()
        Scheduler.plan(context)
        assertEquals(0L, settings.next)
        assertEquals(2, shadowOf(alarms).scheduledAlarms.size)
        settings.enabled = false
        Scheduler.cancel(context)
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }
    @Test fun enablingPlansFutureAlarmAndPersistsTime() {
        settings.enabled = true
        Scheduler.plan(context)
        assertTrue(settings.next > System.currentTimeMillis())
        assertEquals(settings.next, shadowOf(alarms).nextScheduledAlarm!!.triggerAtTime)
    }
    @Test fun replanningDoesNotDuplicateAlarm() {
        settings.enabled = true
        repeat(3) { Scheduler.plan(context) }
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
    }
    @Test fun disabledMonitoringCancelsBothAlarmKinds() {
        settings.enabled = true
        Scheduler.plan(context); Scheduler.retry(context)
        assertEquals(2, shadowOf(alarms).scheduledAlarms.size)
        settings.enabled = false
        Scheduler.plan(context)
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }
    @Test fun retryDoesNotReplaceDailyAlarm() {
        settings.enabled = true
        Scheduler.plan(context)
        val next = settings.next
        Scheduler.retry(context, 300_000)
        assertEquals(next, settings.next)
        assertEquals(2, shadowOf(alarms).scheduledAlarms.size)
    }
    @Test fun existingFutureAlarmSurvivesOrdinaryReplan() {
        settings.enabled = true
        settings.next = System.currentTimeMillis() + 500_000
        val original = settings.next
        Scheduler.plan(context)
        assertEquals(original, settings.next)
    }
}
