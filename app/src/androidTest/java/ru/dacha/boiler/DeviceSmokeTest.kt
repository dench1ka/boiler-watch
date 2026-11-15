package ru.dacha.boiler

import android.content.Context
import android.widget.Button
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
    @Test fun stoppingUnlocksSettingsWithoutLosingSavedValues() {
        settings.prefs.edit().putString("times", "17:45").commit()
        settings.enabled = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val config = Settings(activity)
                activity.window.decorView.findViewWithTag<Button>("tab_settings").performClick()
                activity.window.decorView.findViewWithTag<Button>("section_schedule").performClick()
                assertFalse(activity.window.decorView.findViewWithTag<EditText>("times").isEnabled)
                findButton(activity.window.decorView, "Остановить и настроить")!!.performClick()
                assertEquals("17:45", activity.window.decorView.findViewWithTag<EditText>("times").text.toString())
                assertTrue(activity.window.decorView.findViewWithTag<EditText>("times").isEnabled)
                assertFalse(config.enabled)
            }
        }
    }
    @Test fun mainScreenDoesNotAutoFocusSettingsOrOpenKeyboard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertFalse(activity.currentFocus is EditText) }
        }
    }
    @Test fun mailSettingsSaveAndBlankPasswordPreservesStoredSecret() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                decor.findViewWithTag<Button>("tab_settings").performClick()
                decor.findViewWithTag<Button>("section_delivery").performClick()
                for ((key, value) in mapOf("host" to "smtp.example.org", "port" to "465",
                    "user" to "sender@example.org", "to" to "owner@example.org",
                    "password" to "smtp-only-fixture"))
                    decor.findViewWithTag<EditText>(key).setText(value)
                findButton(decor, "Сохранить настройки")!!.performClick()
                assertTrue(decor.findViewWithTag<View>("page_home").isShown)
                assertEquals("smtp-only-fixture", Settings(activity).password())
                assertEquals("sender@example.org", Settings(activity).from)
                decor.findViewWithTag<Button>("tab_settings").performClick()
                assertEquals("", decor.findViewWithTag<EditText>("password").text.toString())
                decor.findViewWithTag<EditText>("to").setText("new@example.org")
                findButton(decor, "Сохранить настройки")!!.performClick()
                assertEquals("smtp-only-fixture", Settings(activity).password())
                assertEquals("new@example.org", Settings(activity).to)
            }
        }
    }
    @Test fun upgradePreservesExistingKeystorePasswordAndRemovesOldSecret() {
        val password = "upgrade-password-fixture"
        settings.prefs.edit().remove("smtpOnlyMigrated")
            .putString("secret", settings.encrypt(password)).putString("times", "18:30")
            .putString("telegramSecret", settings.encrypt("obsolete-token-fixture"))
            .putBoolean("remoteControl", true).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val upgraded = Settings(activity)
                assertEquals(password, upgraded.password())
                assertEquals("18:30", upgraded.times)
                assertFalse(upgraded.prefs.contains("telegramSecret"))
                assertFalse(upgraded.prefs.contains("remoteControl"))
            }
        }
    }
    @Test fun mainScreenOpensAndCanStopMonitoring() {
        settings.enabled = true
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
    @Test fun navigationKeepsDraftsAndBackReturnsToHome() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                assertTrue(decor.findViewWithTag<View>("page_home").isShown)
                assertFalse(decor.findViewWithTag<View>("page_settings").isShown)
                for (tab in listOf("home", "settings", "help")) assertTrue(decor.findViewWithTag<Button>("tab_$tab").isShown)
                decor.findViewWithTag<Button>("tab_settings").performClick()
                for (section in listOf("schedule", "delivery", "camera")) assertTrue(decor.findViewWithTag<Button>("section_$section").isShown)
                decor.findViewWithTag<Button>("section_schedule").performClick()
                decor.findViewWithTag<EditText>("times").setText("18:40")
                decor.findViewWithTag<Button>("section_delivery").performClick()
                decor.findViewWithTag<EditText>("host").setText("smtp.example.org")
                decor.findViewWithTag<Button>("tab_help").performClick()
                assertFalse(decor.findViewWithTag<EditText>("host").isShown)
                @Suppress("DEPRECATION")
                activity.onBackPressed()
                assertTrue(decor.findViewWithTag<View>("page_home").isShown)
                decor.findViewWithTag<Button>("tab_settings").performClick()
                assertEquals("18:40", decor.findViewWithTag<EditText>("times").text.toString())
                assertEquals("smtp.example.org", decor.findViewWithTag<EditText>("host").text.toString())
                assertTrue(decor.findViewWithTag<EditText>("host").isShown)
                assertFalse(decor.findViewWithTag<EditText>("times").isShown)
                assertEquals("09:00", settings.times)
            }
        }
    }
    @Test fun recreationKeepsSectionAndNonSecretDraftButDoesNotSavePasswordDraft() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                decor.findViewWithTag<Button>("tab_settings").performClick()
                decor.findViewWithTag<Button>("section_delivery").performClick()
                decor.findViewWithTag<EditText>("host").setText("smtp.example.org")
                decor.findViewWithTag<EditText>("password").setText("unsaved-password-fixture")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                assertTrue(decor.findViewWithTag<View>("page_settings").isShown)
                assertTrue(decor.findViewWithTag<EditText>("host").isShown)
                assertEquals("smtp.example.org", decor.findViewWithTag<EditText>("host").text.toString())
                assertTrue(decor.findViewWithTag<EditText>("password").isShown)
                assertEquals("", decor.findViewWithTag<EditText>("password").text.toString())
                assertEquals("", settings.password())
                assertFalse(activity.currentFocus is EditText)
            }
        }
    }
    @Test fun validationOpensTheSettingsSectionThatNeedsAttention() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                findButton(decor, "Включить наблюдение")!!.performClick()
                assertTrue(decor.findViewWithTag<View>("page_settings").isShown)
                assertTrue(decor.findViewWithTag<EditText>("host").isShown)
                assertFalse(settings.enabled)
                decor.findViewWithTag<Button>("section_schedule").performClick()
                decor.findViewWithTag<EditText>("times").setText("bad time")
                findButton(decor, "Сохранить настройки")!!.performClick()
                assertTrue(decor.findViewWithTag<EditText>("times").isShown)
                assertFalse(decor.findViewWithTag<EditText>("host").isShown)
                assertFalse(settings.enabled)
            }
        }
    }
}
