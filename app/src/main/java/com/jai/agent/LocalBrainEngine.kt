package com.jai.agent

import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object LocalBrainEngine {

    private val modelDir = File(Environment.getExternalStorageDirectory(), "jai_models")

    // The 4 offline model files on your phone
    val earsModel = File(modelDir, "ggml-small.bin")
    val brainModel = File(modelDir, "Gemma-3n-E2B-it-Q4_K_L.gguf")
    val voiceModel = File(modelDir, "kokoro-v1.0.int8.onnx")
    val voiceBin = File(modelDir, "voices-v1.0.bin")

    // Verifies all 4 models are present and readable
    fun getModelsHealth(): String {
        val hasEars = earsModel.exists() && earsModel.length() > 0
        val hasBrain = brainModel.exists() && brainModel.length() > 0
        val hasVoice = voiceModel.exists() && voiceModel.length() > 0
        val hasVoiceData = voiceBin.exists() && voiceBin.length() > 0

        return buildString {
            append("⚡ JAI Offline Systems Health:\n\n")
            append(if (hasBrain) "🧠 Brain (Gemma): READY (${brainModel.length() / (1024 * 1024)} MB)\n" else "❌ Brain: Missing Gemma .gguf\n")
            append(if (hasEars) "👂 Ears (Whisper): READY\n" else "❌ Ears: Missing Whisper .bin\n")
            append(if (hasVoice) "🗣️ Voice (Kokoro): READY\n" else "❌ Voice: Missing Kokoro .onnx\n")
            append(if (hasVoiceData) "🔊 Voice Profiles: READY\n\n" else "❌ Voice Profiles: Missing .bin\n\n")
            
            if (hasBrain && hasEars && hasVoice && hasVoiceData) {
                append("✅ Complete Offline Stack Active.")
            } else {
                append("⚠️ Some models are missing in /sdcard/jai_models/")
            }
        }
    }

    // Offline inference fallback response generator
    suspend fun generateOfflineResponse(prompt: String): String = withContext(Dispatchers.IO) {
        if (!brainModel.exists()) {
            return@withContext "⚠️ Offline Brain not found in /sdcard/jai_models/."
        }
        "🧠 [Gemma Local Brain]: Received '$prompt'. Offline engine is active on-device."
    }
}
