package com.wireless.audiostreamer.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Streams low-res JPEG frames from the front or back camera (~8 fps), enough for a live
 * remote-view preview without needing a video codec. [facing] is
 * CameraCharacteristics.LENS_FACING_FRONT or LENS_FACING_BACK.
 */
class CameraCapturer(
    private val context: Context,
    private val facing: Int,
    private val onFrame: (ByteArray) -> Unit
) {
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    @Volatile private var lastFrameMs = 0L

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val bg = HandlerThread("was-camera").also { it.start() }
        bgThread = bg
        val handler = Handler(bg.looper)
        bgHandler = handler
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = findCamera(cm) ?: return false
        val latch = CountDownLatch(1)
        var ok = false
        try {
            val r = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.JPEG, 2)
            reader = r
            r.setOnImageAvailableListener({ rd ->
                val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastFrameMs >= MIN_INTERVAL_MS) {
                        lastFrameMs = now
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        onFrame(bytes)
                    }
                } finally {
                    img.close()
                }
            }, handler)

            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    try {
                        val surface = r.surface
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        req.addTarget(surface)
                        req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        cam.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    try {
                                        s.setRepeatingRequest(req.build(), null, handler)
                                        ok = true
                                    } catch (e: Exception) {
                                    }
                                    latch.countDown()
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    latch.countDown()
                                }
                            },
                            handler
                        )
                    } catch (e: Exception) {
                        latch.countDown()
                    }
                }

                override fun onDisconnected(cam: CameraDevice) {
                    cam.close()
                    device = null
                }

                override fun onError(cam: CameraDevice, error: Int) {
                    cam.close()
                    device = null
                    latch.countDown()
                }
            }, handler)
        } catch (e: Exception) {
            return false
        }
        latch.await(4, TimeUnit.SECONDS)
        return ok
    }

    private fun findCamera(cm: CameraManager): String? {
        for (id in cm.cameraIdList) {
            val c = cm.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) == facing) return id
        }
        return cm.cameraIdList.firstOrNull()
    }

    fun stop() {
        try { session?.close() } catch (e: Exception) { }
        try { device?.close() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        session = null
        device = null
        reader = null
        try { bgThread?.quitSafely() } catch (e: Exception) { }
        bgThread = null
        bgHandler = null
    }

    companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
        const val MIN_INTERVAL_MS = 120L
    }
}
