package com.jai.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class FloatingOverlayService : Service(), TextToSpeech.OnInitListener {

    lateinit var deckManager: OverlayDeckManager
    private var wakeGestureManager: WakeGestureManager? = null

    // Speech & Audio
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var speechRecognizer: SpeechRecognizer? = null

    // Coroutine Lifecycle Management
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceSafe()
        initTextToSpeech()
        initSpeechRecognizer()

        deckManager = OverlayDeckManager(this, serviceScope) { prompt, bitmap ->
            processUserInteraction(prompt, bitmap)
        }

        // Initialize Hardware Gesture Awakener (Proximity wave & shake)
        wakeGestureManager = WakeGestureManager(this) {
            deckManager.updateStatus("Awake! Listening...")
            speakOutResponse("How can I help?")
            startListening()
        }
        wakeGestureManager?.startListening()

        // Hook into JaiAgentService result listener
        JaiAgentService.resultListener = { result ->
            handleActionResult(result)
        }

        try {
            deckManager.buildFloatingDeckUi()
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
                .setContentText("Wave hand or shake to wake")
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
                    override fun onReadyForSpeech(params: Bundle?) { deckManager.updateStatus("Listening...") }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { deckManager.updateStatus("Thinking...") }
                    override fun onError(error: Int) {
                        deckManager.updateStatus("Voice code: $error")
                        FailureLogger.log(applicationContext, "SpeechRecognizer", "Error code: $error")
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()
                        if (!text.isNullOrBlank()) {
                            processUserInteraction(text, deckManager.getCameraOrScreenBitmap())
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

    fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            deckManager.updateStatus("Grant Microphone Permission")
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
            deckManager.updateStatus("Voice start error")
        }
    }

    private fun processUserInteraction(userPrompt: String, inputBitmap: Bitmap?) {
        serviceScope.launch {
            deckManager.updateStatus("Processing: $userPrompt")

            val aiResponse = withContext(Dispatchers.IO) {
                try {
                    AiScreenAnalyzer.analyzeScreenImage(inputBitmap, userPrompt)
                } catch (e: Exception) {
                    FailureLogger.log(applicationContext, "processUserInteraction", "AI Analysis failed: ${e.localizedMessage}")
                    "I encountered an error connecting to the brain."
                }
            }

            try {
                when (val result = JaiAgentService.executeCommand(applicationContext, aiResponse)) {
                    is ActionResult.Success -> {
                        speakOutResponse(result.message)
                        deckManager.updateStatus(result.message)
                    }
                    is ActionResult.Failure -> {
                        speakOutResponse("Sorry, ${result.reason}")
                        deckManager.updateStatus("Failed: ${result.reason}")
                        FailureLogger.log(applicationContext, "executeCommand", "Action failed: ${result.reason}")
                    }
                    is ActionResult.NotAnAction -> {
                        speakOutResponse(aiResponse)
                        deckManager.updateStatus(aiResponse)
                    }
                }
            } catch (e: Exception) {
                FailureLogger.log(applicationContext, "processUserInteraction", "Command execution exception: ${e.localizedMessage}")
                speakOutResponse("Failed to execute command.")
                deckManager.updateStatus("Execution Error")
            }
        }
    }

    private fun handleActionResult(result: ActionResult) {
        when (result) {
            is ActionResult.Success -> {
                speakOutResponse(result.message)
                deckManager.updateStatus(result.message)
            }
            is ActionResult.Failure -> {
                speakOutResponse(result.reason)
                deckManager.updateStatus(result.reason)
            }
            is ActionResult.NotAnAction -> {}
        }
    }

    private fun speakOutResponse(text: String) {
        if (isTtsReady && text.isNotBlank()) {
            try {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JAI_TTS_ID")
            } catch (e: Exception) {
                FailureLogger.log(this, "speakOutResponse", "TTS speak failed: ${e.localizedMessage}")
            }
        }
    }

    override fun onDestroy() {
        try {
            wakeGestureManager?.stopListening()
            JaiAgentService.resultListener = null
            serviceJob.cancel()
            deckManager.destroyDeck()
            tts?.stop()
            tts?.shutdown()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            FailureLogger.log(this, "onDestroy", "Cleanup exception: ${e.localizedMessage}")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
