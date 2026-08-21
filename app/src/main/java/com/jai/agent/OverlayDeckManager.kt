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

    private lateinit var statusTextView: TextView
    private lateinit var promptEditText: EditText
    private lateinit var cameraTextureView: TextureView
    private lateinit var cameraContainer: LinearLayout

    // Camera2 Engine
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var isCameraActive = false
    private var activeLensFacing: Int? = null

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

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
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

        cameraContainer = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        cameraTextureView = TextureView(service).apply {
            layoutParams = LinearLayout.LayoutParams(380, 280).apply {
                gravity = Gravity.CENTER_HORIZONTAL
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
            setOnClickListener {
                runScreenDiagnostics()
            }
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
        windowManager.addView(overlayView, params)
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

    private fun toggleLiveCamera(isFront: Boolean) {
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("Grant Camera Permission")
            return
        }

        val targetFacing = if (isFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK

        if (isCameraActive && activeLensFacing == targetFacing) {
            closeCamera()
            cameraContainer.visibility = View.GONE
            updateStatus("Camera View Closed")
        } else {
            if (isCameraActive) closeCamera()
            cameraContainer.visibility = View.VISIBLE
            if (cameraTextureView.isAvailable) {
                openCamera(isFront)
            } else {
                cameraTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        openCamera(isFront)
                    }
                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(isFront: Boolean) {
        val manager = service.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val targetFacing = if (isFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val targetCameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == targetFacing
            } ?: manager.cameraIdList.firstOrNull() ?: return

            manager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    isCameraActive = true
                    activeLensFacing = targetFacing
                    startCameraPreview()
                    updateStatus("Live Vision: Active")
                }
                override fun onDisconnected(camera: CameraDevice) { closeCamera() }
                override fun onError(camera: CameraDevice, error: Int) {
                    closeCamera()
                    updateStatus("Camera Error ($error)")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            updateStatus("Camera Error")
            FailureLogger.log(service, "openCamera", "${e.localizedMessage}")
        }
    }

    private fun startCameraPreview() {
        val texture = cameraTextureView.surfaceTexture ?: return
        val surface = Surface(texture)
        try {
            val previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                addTarget(surface)
            }

            @Suppress("DEPRECATION")
            cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    previewRequestBuilder?.let { builder ->
                        try {
                            captureSession?.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        } catch (e: Exception) {}
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    updateStatus("Camera Config Failed")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            FailureLogger.log(service, "startCameraPreview", "${e.localizedMessage}")
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            isCameraActive = false
            activeLensFacing = null
        } catch (e: Exception) {}
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            backgroundThread?.join()
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
