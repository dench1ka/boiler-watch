@file:Suppress("DEPRECATION")
package ru.dacha.boiler

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Camera1 supports the legacy HAL used by Redmi 4A; all callbacks share one camera looper. */
object CameraCapture {
    fun take(settings: Settings): ByteArray {
        val thread = HandlerThread("boiler-camera").apply { start() }
        val handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        var result: ByteArray? = null
        var failure: Exception? = null
        var camera: Camera? = null
        var surface: SurfaceTexture? = null
        fun finish(data: ByteArray?, error: Exception?) {
            if (!finished.compareAndSet(false, true)) return
            result = data; failure = error
            handler.removeCallbacksAndMessages(null)
            try { camera?.stopPreview() } catch (_: Exception) { }
            try { camera?.release() } catch (_: Exception) { }
            surface?.release()
            done.countDown(); thread.quitSafely()
        }
        handler.post {
            try {
                val facing = if (settings.front) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK
                val id = (0 until Camera.getNumberOfCameras()).firstOrNull { i ->
                    Camera.CameraInfo().also { Camera.getCameraInfo(i, it) }.facing == facing
                } ?: error("Выбранная камера отсутствует")
                if (settings.context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                    throw SecurityException("Разрешите доступ к камере в настройках приложения")
                val c = Camera.open(id)
                camera = c
                c.setErrorCallback { _, _ -> finish(null, IllegalStateException("Ошибка камеры")) }
                val p = c.parameters
                val sizes = p.supportedPictureSizes
                val size = sizes.maxByOrNull { it.width.toLong() * it.height }
                    ?: error("Камера не сообщила доступные размеры фото")
                p.setPictureSize(size.width, size.height)
                p.setJpegQuality(100); p.setRotation(settings.rotation)
                val modes = p.supportedFocusModes.orEmpty()
                if (Camera.Parameters.FOCUS_MODE_AUTO in modes) p.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
                val flashMode = if (settings.flash) Camera.Parameters.FLASH_MODE_ON else Camera.Parameters.FLASH_MODE_OFF
                if (flashMode in p.supportedFlashModes.orEmpty()) p.flashMode = flashMode
                c.parameters = p
                surface = SurfaceTexture(0)
                c.setPreviewTexture(surface)
                c.startPreview()
                handler.postDelayed({
                    if (!finished.get()) {
                        val fired = AtomicBoolean(false)
                        val shoot = Runnable {
                            if (!finished.get() && fired.compareAndSet(false, true)) {
                                try { c.takePicture(null, null, Camera.PictureCallback { bytes, _ -> finish(bytes, null) }) }
                                catch (e: Exception) { finish(null, e) }
                            }
                        }
                        handler.postDelayed(shoot, 2500)
                        if (p.focusMode == Camera.Parameters.FOCUS_MODE_AUTO) {
                            try { c.autoFocus { _, _ -> handler.post(shoot) } } catch (_: Exception) { handler.post(shoot) }
                        } else handler.post(shoot)
                    }
                }, 1800)
                handler.postDelayed({ finish(null, IllegalStateException("Камера не ответила за 20 секунд")) }, 20_000)
            } catch (e: Exception) { finish(null, e) }
        }
        if (!done.await(25, TimeUnit.SECONDS)) {
            handler.post { finish(null, IllegalStateException("Таймаут камеры")) }
            error("Камера не завершила съёмку")
        }
        failure?.let { throw it }
        return result?.takeIf { it.isNotEmpty() } ?: error("Камера вернула пустой снимок")
    }
}
