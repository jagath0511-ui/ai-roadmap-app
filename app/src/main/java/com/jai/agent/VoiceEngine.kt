package com.jai.agent

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

object VoiceEngine : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingUtterance: String? = null

    fun initialize(context: Context) {
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                val result = engine.setLanguage(Locale.US)
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .build()
                    )
                    engine.setSpeechRate(1.05f)
                    engine.setPitch(1.0f)
                    isInitialized = true

                    pendingUtterance?.let {
                        speak(it)
                        pendingUtterance = null
                    }
                }
            }
        }
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (text.isBlank()) return

        if (!isInitialized) {
            pendingUtterance = text
            return
        }

        onComplete?.let { callback ->
            val utteranceId = "JAI_VOICE_${System.currentTimeMillis()}"
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) callback()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            return
        }

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JAI_DIRECT_SPEECH")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
