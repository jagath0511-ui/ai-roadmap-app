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
    suspend fun capturePhoto(context: Context, useFrontCamera: Boolean): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val thread = HandlerThread("CameraBackgroundThread").apply { start() }
            val handler = Handler(thread.looper)

            try {
                val targetFacing = if (useFrontCamera) {
                    CameraCharacteristics.LENS_FACING_FRONT
                } else {
                    CameraCharacteristics.LENS_FACING_BACK
                }

                var selectedCameraId: String? = null
                for (id in cameraManager.cameraIdList) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    if (characteristics.get(CameraCharacteristics.LENS_FACING) == targetFacing) {
                        selectedCameraId = id
                        break
                    }
                }

                if (selectedCameraId == null) {
                    thread.quitSafely()
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1)

                imageReader.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    image.close()
                    reader.close()
                    thread.quitSafely()
                    if (continuation.isActive) continuation.resume(bitmap)
                }, handler)

                cameraManager.openCamera(selectedCameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(imageReader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }

                        camera.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(
                                            session: CameraCaptureSession,
                                            request: CaptureRequest,
                                            result: TotalCaptureResult
                                        ) {
                                            super.onCaptureCompleted(session, request, result)
                                            camera.close()
                                        }
                                    }, handler)
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    camera.close()
                                    thread.quitSafely()
                                    if (continuation.isActive) continuation.resume(null)
                                }
                            },
                            handler
                        )
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
