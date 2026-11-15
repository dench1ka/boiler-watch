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
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch

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
    @Suppress("DEPRECATION")
    @Test fun cameraProducesJpegAtMaximumSupportedResolution() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val camera = android.hardware.Camera.open()
            val expected = try {
                camera.parameters.supportedPictureSizes.maxOf { it.width.toLong() * it.height }
            } finally { camera.release() }
            val bytes = CameraCapture.take(settings)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull("Camera returned an invalid JPEG", bitmap)
            assertTrue(bitmap!!.width > 0 && bitmap.height > 0)
            assertEquals("Photo must use the largest camera size", expected, bitmap.width.toLong() * bitmap.height)
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
    @Test fun scheduledPhotoAttemptsDeliveryBeforeBrokenOldQueueEntry() {
        val archive = File(context.filesDir, "reports")
        archive.deleteRecursively()
        val broken = File(archive, "pending/000-broken").apply {
            mkdirs(); File(this, "report.json").writeText("broken old report")
        }
        val connected = AtomicBoolean(false)
        // Only accept a local connection and close it. No SMTP account, password or external server.
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 30_000
            val receiver = Thread {
                try { server.accept().use { connected.set(true) } } catch (_: java.io.IOException) { }
            }.apply { isDaemon = true; start() }
            val trigger = System.currentTimeMillis() - 1000
            settings.prefs.edit().putBoolean("enabled", true).putLong("next", trigger)
                .putString("host", "127.0.0.1").putInt("port", server.localPort).putBoolean("ssl", false)
                .putString("user", "local-fixture").putString("from", "sender@example.org")
                .putString("to", "recipient@example.org").commit()
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val intent = Intent(activity, MonitorService::class.java)
                        if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent) else activity.startService(intent)
                    }
                    awaitCondition("No fresh scheduled photo: ${settings.text("problem")}") { settings.lastCapturedSlot == trigger }
                    awaitCondition("Fresh photo was blocked by broken old report") { connected.get() }
                    awaitCondition("Delivery did not finish") { MonitorService.instance?.isBusy == false }
                    val reports = Outbox(context).queue()
                    assertEquals(2, reports.size)
                    val current = reports.single { it != broken }
                    val metadata = Outbox(context).metadata(current)
                    assertEquals(trigger, metadata.getLong("slot"))
                    assertTrue(metadata.getLong("captured") >= trigger)
                    val bitmap = BitmapFactory.decodeFile(File(current, "photo.jpg").absolutePath)
                    assertNotNull("Scheduled photo is not a JPEG", bitmap)
                    bitmap?.recycle()
                }
            } finally {
                settings.enabled = false
                context.stopService(Intent(context, MonitorService::class.java))
                archive.deleteRecursively()
                receiver.join(1000)
            }
        }
    }
    @Test fun triggerDuringArchiveUploadCapturesImmediatelyAfterCurrentRequestFinishes() {
        val archive = File(context.filesDir, "reports")
        archive.deleteRecursively()
        val connected = AtomicBoolean(false)
        val release = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 30_000
            val receiver = Thread {
                try {
                    server.accept().use { connected.set(true); release.await(20, java.util.concurrent.TimeUnit.SECONDS) }
                } catch (_: java.io.IOException) { }
            }.apply { isDaemon = true; start() }
            settings.prefs.edit().putBoolean("enabled", true).putLong("next", System.currentTimeMillis() + 3600_000)
                .putString("host", "127.0.0.1").putInt("port", server.localPort).putBoolean("ssl", false)
                .putString("user", "local-fixture").putString("from", "sender@example.org")
                .putString("to", "recipient@example.org").commit()
            Outbox(context).save(byteArrayOf(1, 2, 3), settings, 1)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val intent = Intent(activity, MonitorService::class.java)
                        if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent) else activity.startService(intent)
                    }
                    awaitCondition("Archive upload did not start") { connected.get() }
                    val trigger = System.currentTimeMillis() - 1000
                    settings.next = trigger
                    // Exercise the real alarm path while the worker is blocked on an old upload.
                    AlarmReceiver().onReceive(context, Intent(context, AlarmReceiver::class.java).setAction("daily"))
                    release.countDown()
                    awaitCondition("Trigger was postponed until a retry alarm: ${settings.text("problem")}") {
                        settings.lastCapturedSlot == trigger
                    }
                }
            } finally {
                settings.enabled = false
                release.countDown()
                server.close()
                context.stopService(Intent(context, MonitorService::class.java))
                receiver.join(1000)
                awaitCondition("Service was not stopped") { MonitorService.instance == null }
                archive.deleteRecursively()
            }
        }
    }
}
