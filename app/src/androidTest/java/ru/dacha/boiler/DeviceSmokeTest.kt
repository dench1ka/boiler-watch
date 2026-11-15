package ru.dacha.boiler

import android.content.Context
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs against real framework/Keystore; never contacts a real mailbox. */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    private lateinit var context: Context
    private lateinit var settings: Settings
    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        settings = Settings(context)
        settings.prefs.edit().clear().commit()
        Scheduler.cancel(context)
    }
    @After fun cleanup() { settings.prefs.edit().clear().commit(); Scheduler.cancel(context) }
    @Test fun passwordSurvivesSettingsRecreationWithoutPlaintextStorage() {
        val password = "device-only-fixture-🔐"
        val encrypted = settings.encrypt(password)
        assertFalse(encrypted.contains(password))
        settings.prefs.edit().putString("secret", encrypted).commit()
        assertEquals(password, Settings(context).password())
        assertFalse(File(context.applicationInfo.dataDir, "shared_prefs/boiler.xml").readText().contains(password))
    }
    @Test fun encryptionUsesFreshNonce() {
        assertNotEquals(settings.encrypt("same-password"), settings.encrypt("same-password"))
    }
    @Test fun modifiedCiphertextIsRejected() {
        val encoded = settings.encrypt("device-fixture")
        val parts = encoded.split(":")
        val bytes = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        settings.prefs.edit().putString("secret", parts[0] + ":" + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).commit()
        assertThrows(Exception::class.java) { settings.password() }
    }
    private fun findButton(view: View, label: String): Button? {
        if (view is Button && view.text.toString() == label) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findButton(view.getChildAt(i), label)?.let { return it }
        return null
    }
    @Test fun stoppingDoesNotOverwriteRemotelyUpdatedFormValues() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val config = Settings(activity)
                config.enabled = true
                config.prefs.edit().putString("times", "17:45").putBoolean("flash", true).commit()
                findButton(activity.window.decorView, "Остановить наблюдение")!!.performClick()
                assertEquals("17:45", activity.window.decorView.findViewWithTag<EditText>("times").text.toString())
                assertFalse(config.enabled)
            }
        }
    }
    @Test fun mainScreenDoesNotAutoFocusSettingsOrOpenKeyboard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertFalse(activity.currentFocus is EditText) }
        }
    }
    @Test fun telegramTokenIsEncryptedInPreferences() {
        val token = "123456:" + "a".repeat(32)
        settings.prefs.edit().putString("telegramSecret", settings.encrypt(token)).commit()
        assertEquals(token, Settings(context).token())
        assertFalse(File(context.applicationInfo.dataDir, "shared_prefs/boiler.xml").readText().contains(token))
    }
    @Test fun telegramOnlySettingsCanBeSavedWithoutSmtpCredentials() {
        settings.prefs.edit().putBoolean("emailPhotos", false).putBoolean("telegramPhotos", true)
            .putLong("telegramChat", 123).putLong("telegramUser", 123).putString("telegramBot", "123456")
            .putString("telegramSecret", settings.encrypt("123456:" + "a".repeat(32))).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.decorView.findViewWithTag<EditText>("times").setText("18:30")
                findButton(activity.window.decorView, "Сохранить настройки")!!.performClick()
                assertEquals("18:30", Settings(activity).times)
                assertFalse(Settings(activity).emailPhotos)
                assertTrue(Settings(activity).telegramPhotos)
            }
        }
    }
    @Test fun unusedMailSettingsAreHiddenWhenEmailIsUnchecked() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                assertTrue(decor.findViewWithTag<EditText>("host").isShown)
                (findButton(decor, "Фото на электронную почту") as CheckBox).isChecked = false
                assertFalse(decor.findViewWithTag<EditText>("host").isShown)
            }
        }
    }
    @Test fun mainScreenOpensAndCanStopMonitoring() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                fun find(view: View): Button? {
                    if (view is Button && view.text.toString() == "Остановить наблюдение") return view
                    if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                    return null
                }
                val button = find(activity.window.decorView)
                assertNotNull(button)
                button!!.performClick()
                assertFalse(Settings(activity).enabled)
            }
        }
    }
}
