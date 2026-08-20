package com.jai.agent

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class FloatingOverlayService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        // 1. Initialize Android Native Text-to-Speech Engine
        tts = TextToSpeech(this, this)

        // 2. Initialize Speech Recognizer
        setupSpeechRecognizer()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
            }
        }
    }

    /**
     * Set up speech recognition listener
     */
    private fun setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    Log.e("VoiceEngine", "Speech Error: $error")
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull()
                    if (!spokenText.isNullOrBlank()) {
                        processUserVoiceCommand(spokenText, null)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    /**
     * Start listening to voice hands-free
     */
    fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
        }
        speechRecognizer?.startListening(intent)
    }

    /**
     * MAIN CONNECTOR: Bridges UI/Voice -> Gemini -> JaiAgentService -> TTS
     */
    fun processUserVoiceCommand(userPrompt: String, screenBitmap: Bitmap?) {
        serviceScope.launch {
            Toast.makeText(applicationContext, "Processing: $userPrompt", Toast.LENGTH_SHORT).show()

            // 1. Call Gemini Cloud API asynchronously
            val aiResponse = withContext(Dispatchers.IO) {
                AiScreenAnalyzer.analyzeScreenImage(screenBitmap, userPrompt)
            }

            // 2. Try executing the response as an Android OS Hardware Command first
            val actionHandled = JaiAgentService.executeCommand(applicationContext, aiResponse)

            if (actionHandled) {
                // If it was a phone action, acknowledge via voice
                speakResponse("Executing action.")
            } else {
                // 3. If it's a conversation answer, speak the entire answer via TTS
                speakResponse(aiResponse)
            }
        }
    }

    /**
     * Speaks text using Text-to-Speech
     */
    private fun speakResponse(text: String) {
        if (isTtsReady && text.isNotBlank()) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JAI_RESPONSE_ID")
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

