package com.jai.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.*
import android.util.Size
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope

class OverlayDeckManager(
    private val service: FloatingOverlayService,
    private val serviceScope: CoroutineScope,
    private val onProcessPrompt: (String, Bitmap?) -> Unit
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private lateinit var windowParams: WindowManager.LayoutParams

    private lateinit var statusTextView: TextView
    private lateinit var promptEditText: EditText
    private lateinit var cameraTextureView: TextureView
    private lateinit var cameraContainer: FrameLayout

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var isCameraActive = false
    private var activeLensFacing: Int? = null
    private var pendingLensFacing: Int? = null
    private var previewSize: Size = Size(640, 480)

    init {
        startBackgroundThread()
    }

    fun buildFloatingDeckUi() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 80
        }

        val rootLayout = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE121212"))
            setPadding(20, 20, 20, 20)
        }

        val titleView = TextView(service).apply {
            text = "⚡ JAI Siri-X Deck"
            setTextColor(Color.CYAN)
            textSize = 15f
        }
        rootLayout.addView(titleView)

        statusTextView = TextView(service).apply {
            text = "Status: Ready"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, 4, 0, 8)
        }
        rootLayout.addView(statusTextView)

        cameraContainer = FrameLayout(service).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                420
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, 8, 0, 8)
            }
            visibility = View.GONE
        }

        cameraTextureView = TextureView(service).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isOpaque = false
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    pendingLensFacing?.let { facing ->
                        openCameraInternal(facing)
                    }
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    closeCamera()
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
        cameraContainer.addView(cameraTextureView)
        rootLayout.addView(cameraContainer)

        promptEditText = EditText(service).apply {
            hint = "Say command or type here..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2C2C2C"))
            setPadding(14, 10, 14, 10)
        }
        rootLayout.addView(promptEditText)

        val row1 = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 0)
        }

        val btnVoice = Button(service).apply {
            text = "🎙️ Voice"
            setOnClickListener { service.startListening() }
        }
        val btnBackCam = Button(service).apply {
            text = "📷 Back"
            setOnClickListener { toggleLiveCamera(isFront = false) }
        }
        val btnFrontCam = Button(service).apply {
            text = "📸 Front"
            setOnClickListener { toggleLiveCamera(isFront = true) }
        }

        row1.addView(btnVoice)
        row1.addView(btnBackCam)
        row1.addView(btnFrontCam)
        rootLayout.addView(row1)

        val row2 = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 0)
        }

        val btnSend = Button(service).apply {
            text = "⚡ Send"
            setOnClickListener {
                val query = promptEditText.text.toString().trim()
                if (query.isNotEmpty()) {
                    promptEditText.setText("")
                    onProcessPrompt(query, getCameraOrScreenBitmap())
                }
            }
        }
        val btnBrain = Button(service).apply {
            text = "🧠 Brain"
            setOnClickListener { runScreenDiagnostics() }
        }
        val btnHide = Button(service).apply {
            text = "❌ Close"
            setOnClickListener {
                closeCamera()
                service.stopSelf()
            }
        }

        row2.addView(btnSend)
        row2.addView(btnBrain)
        row2.addView(btnHide)
        rootLayout.addView(row2)

        overlayView = rootLayout
        windowManager.addView(overlayView, windowParams)
    }

    private fun toggleLiveCamera(isFront: Boolean) {
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("Grant Camera Permission")
            return
        }

        val targetFacing = if (isFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK

        if (isCameraActive && activeLensFacing == targetFacing) {
            closeCamera()
            cameraContainer.visibility = View.GONE
            pendingLensFacing = null
            updateWindowLayout()
            updateStatus("Camera Closed")
            return
        }

        closeCamera()
        activeLensFacing = targetFacing
        pendingLensFacing = targetFacing
        cameraContainer.visibility = View.VISIBLE
        updateWindowLayout()

        if (cameraTextureView.isAvailable) {
            openCameraInternal(targetFacing)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraInternal(targetFacing: Int) {
        val manager = service.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return

        try {
            val targetCameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == targetFacing
            } ?: manager.cameraIdList.firstOrNull() ?: return

            val characteristics = manager.getCameraCharacteristics(targetCameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
            previewSize = sizes?.firstOrNull { it.width <= 1280 && it.height <= 720 } ?: Size(640, 480)

            manager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    isCameraActive = true
                    startCameraPreview()
                    updateStatus("Live Vision: ${if (targetFacing == CameraCharacteristics.LENS_FACING_FRONT) "Front" else "Back"}")
                }

                override fun onDisconnected(camera: CameraDevice) {
                    closeCamera()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    closeCamera()
                    updateStatus("Camera Error: $error")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            FailureLogger.log(service, "openCameraInternal", "${e.localizedMessage}")
            updateStatus("Camera Open Failed")
        }
    }

    private fun startCameraPreview() {
        val device = cameraDevice ?: return
        val texture = cameraTextureView.surfaceTexture ?: return

        try {
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val surface = Surface(texture)

            val previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
                    } catch (e: Exception) {
                        FailureLogger.log(service, "startCameraPreview:setRepeating", "${e.localizedMessage}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    updateStatus("Preview Setup Failed")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            FailureLogger.log(service, "startCameraPreview", "${e.localizedMessage}")
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            isCameraActive = false
        } catch (e: Exception) {
            FailureLogger.log(service, "closeCamera", "${e.localizedMessage}")
        }
    }

    private fun updateWindowLayout() {
        try {
            overlayView?.let {
                windowManager.updateViewLayout(it, windowParams)
            }
        } catch (e: Exception) {
            FailureLogger.log(service, "updateWindowLayout", "${e.localizedMessage}")
        }
    }

    private fun runScreenDiagnostics() {
        val rootNode = JaiAgentService.instance?.rootInActiveWindow
        val rawText = ScreenFilterEngine.extractScreenText(rootNode)
        rootNode?.recycle()

        val analysis = ScreenFilterEngine.evaluateScreenContent(rawText)
        when (analysis.category) {
            ContentCategory.SCAM_RISK -> {
                updateStatus("⚠️ ${analysis.headline}")
                onProcessPrompt("Explain safety risk for text: ${analysis.extractedSummary}", null)
            }
            ContentCategory.PROMOTION -> {
                updateStatus("🛡️ Promo Filtered")
                onProcessPrompt("The screen contains promotional content. Say: '${analysis.extractedSummary}'", null)
            }
            ContentCategory.CLEAN_INFO -> {
                updateStatus("Analyzing context...")
                onProcessPrompt("Summarize active context: ${analysis.extractedSummary}", getCameraOrScreenBitmap())
            }
        }
    }

    fun updateStatus(status: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            statusTextView.text = "Status: $status"
        } else {
            Handler(Looper.getMainLooper()).post {
                statusTextView.text = "Status: $status"
            }
        }
    }

    fun getCameraOrScreenBitmap(): Bitmap? {
        return try {
            if (isCameraActive && cameraTextureView.isAvailable) {
                cameraTextureView.bitmap
            } else {
                null
            }
        } catch (e: Exception) {
            FailureLogger.log(service, "getCameraOrScreenBitmap", "Failed to capture bitmap: ${e.localizedMessage}")
            null
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {}
    }

    fun destroyDeck() {
        closeCamera()
        stopBackgroundThread()
        if (overlayView != null) {
            windowManager.removeView(overlayView)
        }
    }
}
