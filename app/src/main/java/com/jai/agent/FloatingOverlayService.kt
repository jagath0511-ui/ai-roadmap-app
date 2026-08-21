package com.jai.agent

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class FloatingOverlayService : Service(), TextToSpeech.OnInitListener {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    // UI Elements
    private lateinit var statusTextView: TextView
    private lateinit var promptEditText: EditText
    private lateinit var cameraTextureView: TextureView
    private lateinit var cameraContainer: LinearLayout

    // Speech & Audio
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var speechRecognizer: SpeechRecognizer? = null

    // Coroutine Lifecycle Management
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // Camera2 Engine
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var isCameraActive = false
    private var activeLensFacing: Int? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceSafe()
        initTextToSpeech()
        initSpeechRecognizer()
        startBackgroundThread()

        // Hook into JaiAgentService result listener to broadcast ActionResult feedback asynchronously
        JaiAgentService.resultListener = { result ->
            handleActionResult(result)
        }

        try {
            buildFloatingDeckUi()
        } catch (e: WindowManager.BadTokenException) {
            FailureLogger.log(this, "FloatingOverlayService", "WindowManager BadTokenException: ${e.localizedMessage}")
            stopSelf()
        } catch (e: Exception) {
            FailureLogger.log(this, "FloatingOverlayService", "Failed to build floating deck UI: ${e.localizedMessage}")
            stopSelf()
        }
    }

    private fun startForegroundServiceSafe() {
        try {
            val channelId = "jai_overlay_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "JAI Floating Service",
                    NotificationManager.IMPORTANCE_LOW
                )
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            }

            val notification: Notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("JAI Assistant Active")
                .setContentText("Hands-free Agent running")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            startForeground(101, notification)
        } catch (e: Exception) {
            FailureLogger.log(this, "startForegroundServiceSafe", "Failed to start foreground service: ${e.localizedMessage}")
        }
    }

    private fun initTextToSpeech() {
        try {
            tts = TextToSpeech(this, this)
        } catch (e: Exception) {
            FailureLogger.log(this, "initTextToSpeech", "Failed to initialize TTS: ${e.localizedMessage}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
            } else {
                FailureLogger.log(this, "TextToSpeech", "Language data missing or unsupported.")
            }
        } else {
            FailureLogger.log(this, "TextToSpeech", "Initialization failed with status: $status")
        }
    }

    private fun initSpeechRecognizer() {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { updateStatus("Listening...") }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { updateStatus("Thinking...") }
                    override fun onError(error: Int) {
                        updateStatus("Voice code: $error")
                        FailureLogger.log(applicationContext, "SpeechRecognizer", "Error code: $error")
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()
                        if (!text.isNullOrBlank()) {
                            processUserInteraction(text, getCameraOrScreenBitmap())
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            FailureLogger.log(this, "initSpeechRecognizer", "Failed to create SpeechRecognizer: ${e.localizedMessage}")
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("Grant Microphone Permission")
            FailureLogger.log(this, "startListening", "Record audio permission denied.")
            return
        }
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            FailureLogger.log(this, "startListening", "Failed to start listening: ${e.localizedMessage}")
            updateStatus("Voice start error")
        }
    }

    private fun buildFloatingDeckUi() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

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

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE121212"))
            setPadding(20, 20, 20, 20)
        }

        val titleView = TextView(this).apply {
            text = "⚡ JAI Siri-X Deck"
            setTextColor(Color.CYAN)
            textSize = 15f
        }
        rootLayout.addView(titleView)

        statusTextView = TextView(this).apply {
            text = "Status: Ready"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, 4, 0, 8)
        }
        rootLayout.addView(statusTextView)

        cameraContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        cameraTextureView = TextureView(this).apply {
            layoutParams = LinearLayout.LayoutParams(380, 280).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        cameraContainer.addView(cameraTextureView)
        rootLayout.addView(cameraContainer)

        promptEditText = EditText(this).apply {
            hint = "Say command or type here..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2C2C2C"))
            setPadding(14, 10, 14, 10)
        }
        rootLayout.addView(promptEditText)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 0)
        }

        val btnVoice = Button(this).apply {
            text = "🎙️ Voice"
            setOnClickListener { startListening() }
        }
        val btnBackCam = Button(this).apply {
            text = "📷 Back"
            setOnClickListener { toggleLiveCamera(isFront = false) }
        }
        val btnFrontCam = Button(this).apply {
            text = "📸 Front"
            setOnClickListener { toggleLiveCamera(isFront = true) }
        }

        row1.addView(btnVoice)
        row1.addView(btnBackCam)
        row1.addView(btnFrontCam)
        rootLayout.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 0)
        }

        val btnSend = Button(this).apply {
            text = "⚡ Send"
            setOnClickListener {
                val query = promptEditText.text.toString().trim()
                if (query.isNotEmpty()) {
                    promptEditText.setText("")
                    processUserInteraction(query, getCameraOrScreenBitmap())
                }
            }
        }
        val btnBrain = Button(this).apply {
            text = "🧠 Brain"
            setOnClickListener {
                updateStatus("Analyzing context...")
                processUserInteraction("Diagnose active view and app context", getCameraOrScreenBitmap())
            }
        }
        val btnHide = Button(this).apply {
            text = "❌ Close"
            setOnClickListener {
                closeCamera()
                stopSelf()
            }
        }

        row2.addView(btnSend)
        row2.addView(btnBrain)
        row2.addView(btnHide)
        rootLayout.addView(row2)

        overlayView = rootLayout
        windowManager.addView(overlayView, params)
    }

    private fun toggleLiveCamera(isFront: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("Grant Camera Permission")
            FailureLogger.log(this, "toggleLiveCamera", "Camera permission denied.")
            return
        }

        val targetFacing = if (isFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK

        if (isCameraActive && activeLensFacing == targetFacing) {
            closeCamera()
            cameraContainer.visibility = View.GONE
            updateStatus("Camera View Closed")
        } else {
            if (isCameraActive) {
                closeCamera()
            }
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
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val targetFacing = if (isFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val targetCameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == targetFacing
            } ?: manager.cameraIdList.firstOrNull()

            if (targetCameraId == null) {
                updateStatus("No camera found")
                FailureLogger.log(this, "openCamera", "No valid camera ID found for facing: $targetFacing")
                return
            }

            manager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    isCameraActive = true
                    activeLensFacing = targetFacing
                    startCameraPreview()
                    updateStatus("Live Vision: Active")
                }
                override fun onDisconnected(camera: CameraDevice) {
                    closeCamera()
                    FailureLogger.log(applicationContext, "openCamera", "Camera disconnected.")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    closeCamera()
                    updateStatus("Camera Error ($error)")
                    FailureLogger.log(applicationContext, "openCamera", "Camera error code: $error")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            updateStatus("Camera Access Error")
            FailureLogger.log(this, "openCamera", "CameraAccessException: ${e.localizedMessage}")
        } catch (e: SecurityException) {
            updateStatus("Camera Security Exception")
            FailureLogger.log(this, "openCamera", "SecurityException: ${e.localizedMessage}")
        } catch (e: Exception) {
            updateStatus("Camera Init Error")
            FailureLogger.log(this, "openCamera", "Exception: ${e.localizedMessage}")
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
                        } catch (e: CameraAccessException) {
                            FailureLogger.log(applicationContext, "startCameraPreview", "CameraAccessException during repeat request: ${e.localizedMessage}")
                        }
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    updateStatus("Camera Config Failed")
                    FailureLogger.log(applicationContext, "startCameraPreview", "Camera capture session configuration failed.")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            FailureLogger.log(this, "startCameraPreview", "CameraAccessException: ${e.localizedMessage}")
        } catch (e: IllegalStateException) {
            FailureLogger.log(this, "startCameraPreview", "IllegalStateException (camera closed): ${e.localizedMessage}")
        } catch (e: Exception) {
            FailureLogger.log(this, "startCameraPreview", "Exception: ${e.localizedMessage}")
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
        } catch (e: Exception) {
            FailureLogger.log(this, "closeCamera", "Error closing camera: ${e.localizedMessage}")
        }
    }

    private fun getCameraOrScreenBitmap(): Bitmap? {
        return try {
            if (isCameraActive && cameraTextureView.isAvailable) {
                cameraTextureView.bitmap
            } else {
                null
            }
        } catch (e: Exception) {
            FailureLogger.log(this, "getCameraOrScreenBitmap", "Failed to capture bitmap from TextureView: ${e.localizedMessage}")
            null
        }
    }

    private fun processUserInteraction(userPrompt: String, inputBitmap: Bitmap?) {
        serviceScope.launch {
            updateStatus("Processing: $userPrompt")

            val aiResponse = withContext(Dispatchers.IO) {
                try {
                    AiScreenAnalyzer.analyzeScreenImage(inputBitmap, userPrompt)
                } catch (e: Exception) {
                    FailureLogger.log(applicationContext, "processUserInteraction", "AI Analysis failed: ${e.localizedMessage}")
                    "I encountered an error connecting to the brain."
                }
            }

            try {
                // Handle both device actions and conversational text answers (`ActionResult.NotAnAction`)
                when (val result = JaiAgentService.executeCommand(applicationContext, aiResponse)) {
                    is ActionResult.Success -> {
                        speakOutResponse(result.message)
                        updateStatus(result.message)
                    }
                    is ActionResult.Failure -> {
                        speakOutResponse("Sorry, ${result.reason}")
                        updateStatus("Failed: ${result.reason}")
                        FailureLogger.log(applicationContext, "executeCommand", "Action failed: ${result.reason}")
                    }
                    is ActionResult.NotAnAction -> {
    
