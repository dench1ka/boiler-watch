package ru.dacha.boiler

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 35])
class RemoteCommandsTest {
    private lateinit var s: Settings
    private lateinit var commands: RemoteCommands
    private var changes = 0
    private var photos = 0
    @Before fun setup() {
        s = Settings(RuntimeEnvironment.getApplication())
        s.prefs.edit().clear().putBoolean("enabled", true).putBoolean("remoteControl", true)
            .putLong("telegramChat", 123).putLong("telegramUser", 123).commit()
        commands = RemoteCommands(s, { changes++ }, { photos++ })
        changes = 0; photos = 0
    }
    private fun update(text: String, user: Long = 123, id: Long = 1, age: Long = 0): JSONObject = JSONObject()
        .put("update_id", id).put("message", JSONObject().put("text", text).put("date", System.currentTimeMillis() / 1000 - age)
            .put("from", JSONObject().put("id", user).put("is_bot", false))
            .put("chat", JSONObject().put("id", user).put("type", "private")))
    @Test fun readableButtonsPerformOwnerActions() {
        commands.process(update("Пауза")); assertTrue(s.paused)
        commands.process(update("Продолжить", id = 2)); assertFalse(s.paused)
        commands.process(update("Фото сейчас", id = 3)); assertEquals(1, photos)
    }
    @Test fun readableButtonsDoNotGrantAccessToStrangers() {
        assertNull(commands.process(update("Фото сейчас", 999))); assertEquals(0, photos)
    }
    @Test fun ownerCanChangeTimesAndReschedule() {
        assertTrue(commands.process(update("/times 08:00, 20:30"))!!.contains("сохранено"))
        assertEquals("08:00, 20:30", s.times); assertEquals(1, changes)
    }
    @Test fun invalidTimesDoNotChangeSettings() {
        commands.process(update("/times 24:00")); assertEquals("09:00", s.times); assertEquals(0, changes)
    }
    @Test fun strangerCannotChangeSettingsOrGetReply() {
        assertNull(commands.process(update("/times 10:00", 999))); assertEquals("09:00", s.times)
    }
    @Test fun groupCommandsAreIgnoredEvenFromOwner() {
        val u = update("/photo"); u.getJSONObject("message").getJSONObject("chat").put("type", "group")
        assertNull(commands.process(u)); assertEquals(0, photos)
    }
    @Test fun mismatchedPrivateChatIsIgnored() {
        val u = update("/photo"); u.getJSONObject("message").getJSONObject("chat").put("id", 456)
        assertNull(commands.process(u)); assertEquals(0, photos)
    }
    @Test fun stalePhotoCommandIsNotExecuted() {
        assertTrue(commands.process(update("/photo", age = 301))!!.contains("устарела")); assertEquals(0, photos)
    }
    @Test fun futureDatedCommandsAreNotExecuted() { commands.process(update("/photo", age = -120)); assertEquals(0, photos) }
    @Test fun duplicatePhotoCommandIsExecutedOnlyOnce() {
        val u = update("/photo", id = 42)
        commands.process(u); commands.process(u)
        assertEquals(1, photos); assertEquals(42L, s.prefs.getLong("remotePhoto", 0))
        assertEquals(43L, s.prefs.getLong("telegramOffset", 0))
    }
    @Test fun remoteControlSwitchIsRespected() {
        s.prefs.edit().putBoolean("remoteControl", false).commit()
        assertNull(commands.process(update("/photo"))); assertEquals(0, photos)
    }
    @Test fun stoppedMonitoringCannotBeRestartedRemotely() {
        s.enabled = false; assertNull(commands.process(update("/resume"))); assertFalse(s.enabled)
    }
    @Test fun pauseKeepsMonitoringAndRemoteConnectionEnabled() {
        commands.process(update("/pause")); assertTrue(s.enabled); assertTrue(s.paused); assertTrue(s.remoteControl)
        commands.process(update("/resume", id = 2)); assertFalse(s.paused); assertEquals(2, changes)
    }
    @Test fun pairingRequiresExactSecretAndPrivateChat() {
        s.enabled = false
        s.prefs.edit().putString("pairCode", "12345678").putLong("pairUntil", System.currentTimeMillis() + 60_000).commit()
        assertNull(commands.process(update("/pair wrong", 999)))
        assertNotNull(commands.process(update("/pair 12345678", 999, 2)))
        assertEquals(999L, s.telegramUser); assertEquals(999L, s.telegramChat); assertFalse(s.pairing)
    }
    @Test fun expiredPairingCannotBindStranger() {
        s.enabled = false
        s.prefs.edit().putString("pairCode", "12345678").putLong("pairUntil", System.currentTimeMillis() - 1).commit()
        assertNull(commands.process(update("/pair 12345678", 999))); assertEquals(123L, s.telegramUser)
    }
    @Test fun pairingCannotBeReplayedAfterSuccess() {
        s.prefs.edit().putString("pairCode", "12345678").putLong("pairUntil", System.currentTimeMillis() + 60_000).commit()
        commands.process(update("/pair 12345678"))
        assertNull(commands.process(update("/pair 12345678", 999, 2))); assertEquals(123L, s.telegramUser)
    }
    @Test fun invalidRecipientDoesNotOverwriteAddress() {
        s.prefs.edit().putString("to", "owner@example.org").commit()
        commands.process(update("/recipient invalid")); assertEquals("owner@example.org", s.to)
    }
    @Test fun heartbeatAndCameraCommandsValidateValues() {
        commands.process(update("/alive on")); assertTrue(s.heartbeat)
        commands.process(update("/flash on", id = 2)); assertTrue(s.flash)
        commands.process(update("/rotation 17", id = 3)); assertEquals(90, s.rotation)
        commands.process(update("/rotation 180", id = 4)); assertEquals(180, s.rotation)
    }
    @Test fun noPhotoIsAcceptedWithoutDeliveryRoute() {
        s.prefs.edit().putBoolean("emailPhotos", false).putBoolean("telegramPhotos", false).commit()
        commands.process(update("/photo")); assertEquals(0, photos)
    }
    @Test fun botMessagesAndEditedUpdatesAreIgnored() {
        val u = update("/photo");u.getJSONObject("message").getJSONObject("from").put("is_bot", true)
        assertNull(commands.process(u))
        val edited = JSONObject().put("update_id", 2).put("edited_message", update("/photo").getJSONObject("message"))
        assertNull(commands.process(edited)); assertEquals(0, photos)
    }
}
