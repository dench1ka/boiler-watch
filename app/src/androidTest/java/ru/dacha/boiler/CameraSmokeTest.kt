package ru.dacha.boiler

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Use an emulator with -camera-back emulated; does not access a host webcam or send mail. */
@RunWith(AndroidJUnit4::class)
class CameraSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var settings: Settings
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        Unit
    }
    private fun awaitCondition(message: String, timeout: Long = 30_000, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeout
        while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue(message, condition())
    }
    @Before fun setup() {
        context = instrumentation.targetContext
        settings = Settings(context)
        settings.prefs.edit().clear().putInt("rotation", 0).commit()
        File(context.filesDir, "preview.jpg").delete()
        shell("pm grant ${context.packageName} ${Manifest.permission.CAMERA}")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
    }
    @After fun cleanup() {
        settings.enabled = false
        context.stopService(Intent(context, MonitorService::class.java))
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        Scheduler.cancel(context)
    }
    @Test fun cameraProducesDecodableJpeg() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val bytes = CameraCapture.take(settings)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull("Camera returned an invalid JPEG", bitmap)
            assertTrue(bitmap!!.width > 0 && bitmap.height > 0)
            bitmap.recycle()
        }
    }
    @Test fun foregroundServiceCapturesWithScreenOffWithoutSendingMail() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val intent = Intent(activity, MonitorService::class.java).putExtra("manual", true).putExtra("preview", true)
                if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent) else activity.startService(intent)
            }
            awaitCondition("Camera foreground service did not start", 5000) { MonitorService.instance != null }
            scenario.moveToState(Lifecycle.State.CREATED)
            shell("input keyevent KEYCODE_SLEEP")
            val preview = File(context.filesDir, "preview.jpg")
            awaitCondition("No preview captured: ${settings.text("problem")}") { preview.exists() && MonitorService.instance == null }
            val bitmap = BitmapFactory.decodeFile(preview.absolutePath)
            assertNotNull("Background capture is not a JPEG", bitmap)
            bitmap?.recycle()
            assertEquals("Preview must never enter the mail queue", 0, Outbox(context).count())
        }
    }
}
