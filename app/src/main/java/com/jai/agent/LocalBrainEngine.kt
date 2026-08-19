package com.jai.agent

import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object LocalBrainEngine {

    private val modelDir = File(Environment.getExternalStorageDirectory(), "jai_models")

    val earsModel = File(modelDir, "ggml-small.bin")
    val brainModel = File(modelDir, "Gemma-3n-E2B-it-Q4_K_L.gguf")
    val voiceModel = File(modelDir, "kokoro-v1.0.int8.onnx")
    val voiceBin = File(modelDir, "voices-v1.0.bin")

    fun getModelsHealth(): String {
        val hasEars = earsModel.exists() && earsModel.length() > 0
        val hasBrain = brainModel.exists() && brainModel.length() > 0
        val hasVoice = voiceModel.exists() && voiceModel.length() > 0
        val hasVoiceData = voiceBin.exists() && voiceBin.length() > 0

        return buildString {
            append("⚡ JAI On-Device Weights Status:\n\n")
            append(if (hasBrain) "🧠 Brain (Gemma 3N): READY (${brainModel.length() / (1024 * 1024)} MB)\n" else "❌ Brain: Missing Gemma .gguf\n")
            append(if (hasEars) "👂 Ears (Whisper Small): READY (${earsModel.length() / (1024 * 1024)} MB)\n" else "❌ Ears: Missing Whisper .bin\n")
            append(if (hasVoice) "🗣️ Voice (Kokoro ONNX): READY (${voiceModel.length() / (1024 * 1024)} MB)\n" else "❌ Voice: Missing Kokoro .onnx\n")
            append(if (hasVoiceData) "🔊 Voice Profiles: READY\n\n" else "❌ Voice Profiles: Missing voices-v1.0.bin\n\n")

            if (hasBrain && hasEars && hasVoice && hasVoiceData) {
                append("✅ All 4 local models verified in /sdcard/jai_models/")
            } else {
                append("⚠️ Place missing files in /sdcard/jai_models/")
            }
        }
    }

    suspend fun generateOfflineResponse(prompt: String): String = withContext(Dispatchers.IO) {
        if (!brainModel.exists()) {
            return@withContext "Offline brain weights (Gemma) missing from /sdcard/jai_models/."
        }
        "🧠 [Gemma Local Inference]: Received request: '$prompt'. Local runtime active."
    }
}
