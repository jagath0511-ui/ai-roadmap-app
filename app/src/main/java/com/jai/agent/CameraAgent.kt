package com.jai.agent

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object CameraAgent {

    @SuppressLint("MissingPermission")
    suspend fun capturePhoto(context: Context, useFrontCamera: Boolean): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val thread = HandlerThread("CameraWorkerThread").apply { start() }
            val handler = Handler(thread.looper)
            val isResumed = AtomicBoolean(false)

            fun cleanup(device: CameraDevice?, reader: ImageReader?) {
                try { device?.close() } catch (ignored: Exception) {}
                try { reader?.close() } catch (ignored: Exception) {}
                try { thread.quitSafely() } catch (ignored: Exception) {}
            }

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
                    cleanup(null, null)
                    if (isResumed.compareAndSet(false, true)) continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1)

                cameraManager.openCamera(selectedCameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        imageReader.setOnImageAvailableListener({ reader ->
                            val image = reader.acquireLatestImage()
                            var resultBitmap: Bitmap? = null
                            if (image != null) {
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                resultBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                image.close()
                            }
                            cleanup(camera, imageReader)
                            if (isResumed.compareAndSet(false, true)) {
                                continuation.resume(resultBitmap)
                            }
                        }, handler)

                        try {
                            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(imageReader.surface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }

                            camera.createCaptureSession(
                                listOf(imageReader.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        try {
                                            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureCompleted(
                                                    session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    result: TotalCaptureResult
                                                ) {
                                                    super.onCaptureCompleted(session, request, result)
                                                }
                                            }, handler)
                                        } catch (e: Exception) {
                                            cleanup(camera, imageReader)
                                            if (isResumed.compareAndSet(false, true)) continuation.resume(null)
                                        }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        cleanup(camera, imageReader)
                                        if (isResumed.compareAndSet(false, true)) continuation.resume(null)
                                    }
                                },
                                handler
                            )
                        } catch (e: Exception) {
                            cleanup(camera, imageReader)
                            if (isResumed.compareAndSet(false, true)) continuation.resume(null)
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        cleanup(camera, imageReader)
                        if (isResumed.compareAndSet(false, true)) continuation.resume(null)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        cleanup(camera, imageReader)
                        if (isResumed.compareAndSet(false, true)) continuation.resume(null)
                    }
                }, handler)

            } catch (e: Exception) {
                cleanup(null, null)
                if (isResumed.compareAndSet(false, true)) continuation.resume(null)
            }
        }
}
