package com.jai.agent

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object CameraAgent {

    @SuppressLint("MissingPermission")
    suspend fun captureSnapshot(context: Context, useFrontCamera: Boolean = false): Bitmap? {
        return suspendCancellableCoroutine { continuation ->
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cameraManager == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val targetFacing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == targetFacing
            } ?: cameraManager.cameraIdList.firstOrNull()

            if (cameraId == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val thread = HandlerThread("CameraAgentThread").also { it.start() }
            val handler = Handler(thread.looper)

            val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2)
            var cameraDevice: CameraDevice? = null

            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                cameraDevice?.close()
                thread.quitSafely()
                if (continuation.isActive) continuation.resume(bitmap)
            }, handler)

            try {
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        try {
                            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(imageReader.surface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }
                            @Suppress("DEPRECATION")
                            camera.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        session.capture(captureBuilder.build(), null, handler)
                                    } catch (e: Exception) {
                                        camera.close()
                                        thread.quitSafely()
                                        if (continuation.isActive) continuation.resume(null)
                                    }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    camera.close()
                                    thread.quitSafely()
                                    if (continuation.isActive) continuation.resume(null)
                                }
                            }, handler)
                        } catch (e: Exception) {
                            camera.close()
                            thread.quitSafely()
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        thread.quitSafely()
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        thread.quitSafely()
                        if (continuation.isActive) continuation.resume(null)
                    }
                }, handler)
            } catch (e: Exception) {
                thread.quitSafely()
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }
}
