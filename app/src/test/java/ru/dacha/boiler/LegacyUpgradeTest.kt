package ru.dacha.boiler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
class LegacyUpgradeTest {
    private lateinit var context: Context
    private lateinit var alarms: AlarmManager
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("boiler", Context.MODE_PRIVATE).edit().clear().commit()
        alarms = context.getSystemService(AlarmManager::class.java)
    }
    private fun oldAlarm(code: Int, action: String) {
        val pending = PendingIntent.getBroadcast(context, code,
            Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60_000, pending)
    }
    @Test fun upgradeRemovesOldSecretsAndWakeupsButPreservesMailAndDailyAlarm() {
        val prefs = context.getSharedPreferences("boiler", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("enabled", true).putBoolean("emailPhotos", true)
            .putString("host", "smtp.example.org").putString("user", "owner@example.org")
            .putString("secret", "encrypted-fixture").putString("to", "recipient@example.org")
            .putString("times", "09:00, 18:30").putString("telegramSecret", "obsolete-secret-fixture")
            .putBoolean("remoteControl", true).putBoolean("heartbeat", true)
            .putLong("remotePhoto", 123).putString("pairCode", "12345678").commit()
        oldAlarm(1, "daily"); oldAlarm(3, "telegram"); oldAlarm(4, "heartbeat")
        val settings = Settings(context)
        assertTrue(settings.enabled)
        assertEquals("smtp.example.org", settings.host)
        assertEquals("owner@example.org", settings.user)
        assertEquals("recipient@example.org", settings.to)
        assertEquals("09:00, 18:30", settings.times)
        assertEquals("encrypted-fixture", prefs.getString("secret", ""))
        for (key in listOf("telegramSecret", "remoteControl", "heartbeat", "remotePhoto", "pairCode", "emailPhotos"))
            assertFalse(prefs.contains(key))
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
        // A second Settings instance does not remove the newly planned mail alarm.
        Scheduler.plan(context)
        Settings(context)
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
    }
    @Test fun previousPauseDoesNotUnexpectedlyResumeTakingPhotos() {
        val prefs = context.getSharedPreferences("boiler", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("enabled", true).putBoolean("paused", true).putLong("pendingCapture", 123).commit()
        oldAlarm(1, "daily"); oldAlarm(2, "retry"); oldAlarm(3, "telegram")
        val settings = Settings(context)
        assertFalse(settings.enabled)
        assertEquals(0L, settings.pendingCapture)
        assertFalse(prefs.contains("paused"))
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }
    @Test fun previousNonMailModeRequiresLocalMailSetup() {
        val prefs = context.getSharedPreferences("boiler", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("enabled", true).putBoolean("emailPhotos", false).commit()
        assertFalse(Settings(context).enabled)
    }
    @Test fun normalStoppedInstallationRemainsStopped() {
        val prefs = context.getSharedPreferences("boiler", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("enabled", false).putString("problem", "SMTP: fixture").commit()
        assertFalse(Settings(context).enabled)
        assertEquals("SMTP: fixture", prefs.getString("problem", ""))
    }
}
