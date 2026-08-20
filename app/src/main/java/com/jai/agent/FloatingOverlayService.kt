package com.jai.agent

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

    // Coroutine Lifecycle Safe
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
        // 1. Safe Standard Foreground Service Start (Never crashes on Android 14)
        startForegroundServiceSafe()
        initTextToSpeech()
        initSpeechRecognizer()
        startBackgroundThread()
        buildFloatingDeckUi()
    }

    private fun startForegroundServiceSafe() {
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

        // Standard startForeground avoids Android 14 SecurityException
        startForeground(101, notification)
    }

    private fun initTextToSpeech() {
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
            }
        }
    }

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    updateStatus("Listening...")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    updateStatus("Thinking...")
                }
                override fun onError(error: Int) {
                    updateStatus("Voice code: $error")
                }
                override fun onResults(results: android.os.Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        processUserInteraction(text, getCameraOrScreenBitmap())
                    }
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
        }
    }

    private fun startListening() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("Grant Microphone Permission in Settings")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
        }
        speechRecognizer?.startListening(intent)
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
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("Grant Camera Permission in Settings")
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
            } ?: manager.cameraIdList.firstOrNull() ?: return

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
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    closeCamera()
                    updateStatus("Camera Error ($error)")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            updateStatus("Camera Error: ${e.localizedMessage}")
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
                        captureSession?.setRepeatingRequest(builder.build(), null, backgroundHandler)
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    updateStatus("Camera Config Failed")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e("CameraVision", "Preview error: ${e.localizedMessage}")
        }
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        isCameraActive = false
        activeLensFacing = null
    }

    private fun getCameraOrScreenBitmap(): Bitmap? {
        return if (isCameraActive && cameraTextureView.isAvailable) {
            cameraTextureView.bitmap
        } else {
            null
        }
    }

    fun processUserInteraction(userPrompt: String, inputBitmap: Bitmap?) {
        serviceScope.launch {
            updateStatus("Processing: $userPrompt")

            val aiResponse = withContext(Dispatchers.IO) {
                AiScreenAnalyzer.analyzeScreenImage(inputBitmap, userPrompt)
            }

            val actionHandled = JaiAgentService.executeCommand(applicationContext, aiResponse)

            if (actionHandled) {
                speakOutResponse("Action executed.")
                updateStatus("Action Completed")
            } else {
                speakOutResponse(aiResponse)
                updateStatus(aiResponse)
            }
        }
    }

    private fun speakOutResponse(text: String) {
        if (isTtsReady && text.isNotBlank()) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JAI_TTS_ID")
        }
    }

    private fun updateStatus(status: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            statusTextView.text = "Status: $status"
        } else {
            Handler(Looper.getMainLooper()).post {
                statusTextView.text = "Status: $status"
            }
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e("CameraVision", "Error: ${e.localizedMessage}")
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()
        closeCamera()
        stopBackgroundThread()
        if (overlayView != null) {
            windowManager.removeView(overlayView)
        }
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

