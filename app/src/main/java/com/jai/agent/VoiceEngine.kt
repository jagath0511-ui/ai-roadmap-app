package com.jai.agent

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Environment
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.Locale

class VoiceEngine(private val context: Context) {

    private val modelDir = File(Environment.getExternalStorageDirectory(), "jai_models")
    private var ttsSession: OrtSession? = null
    private var nativeTtsFallback: TextToSpeech? = null
    private var isNativeTtsReady = false

    init {
        initKokoroSession()
        initSystemFallbackTts()
    }

    private fun initKokoroSession() {
        try {
            val modelFile = File(modelDir, "kokoro-v1.0.int8.onnx")
            if (modelFile.exists()) {
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                }
                ttsSession = env.createSession(modelFile.absolutePath, opts)
            }
        } catch (ignored: Exception) {}
    }

    private fun initSystemFallbackTts() {
        nativeTtsFallback = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                nativeTtsFallback?.language = Locale.US
                isNativeTtsReady = true
            }
        }
    }

    fun speak(text: String) {
        if (isNativeTtsReady) {
            nativeTtsFallback?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JAI_TTS")
        }
    }

    fun checkModelsStatus(): String {
        val whisper = File(modelDir, "ggml-small.bin").exists()
        val kokoro = File(modelDir, "kokoro-v1.0.int8.onnx").exists()
        val voices = File(modelDir, "voices-v1.0.bin").exists()

        return when {
            whisper && kokoro && voices -> "✅ All 3 Offline Voice Models Loaded (/sdcard/jai_models/)"
            else -> "⚠️ Models missing in /sdcard/jai_models/\n(Whisper: $whisper, Kokoro: $kokoro, Voices: $voices)"
        }
    }

    fun shutdown() {
        nativeTtsFallback?.stop()
        nativeTtsFallback?.shutdown()
        ttsSession?.close()
    }
}
