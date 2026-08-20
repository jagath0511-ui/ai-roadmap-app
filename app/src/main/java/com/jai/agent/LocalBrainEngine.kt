package com.jai.agent

import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object LocalBrainEngine {

    private val modelDir = File(Environment.getExternalStorageDirectory(), "jai_models")

    val earsModel = File(modelDir, "ggml-small.bin")
    val voiceModel = File(modelDir, "kokoro-v1.0.int8.onnx")
    val voiceBin = File(modelDir, "voices-v1.0.bin")

    /**
     * Dynamically finds ANY installed Gemma GGUF model in /sdcard/jai_models/
     */
    fun getActiveBrainModelFile(): File? {
        if (!modelDir.exists()) return null
        
        // Priority list of models
        val supportedNames = listOf(
            "gemma-2-2b-it-Q4_K_M.gguf",
            "gemma-2-2b-it.q4_k_m.gguf",
            "gemma-3n-E4B-it-Q4_K_L.gguf",
            "gemma-3n-E4B-it-Q4_K_M.gguf",
            "Gemma-3n-E2B-it-Q4_K_L.gguf"
        )

        for (name in supportedNames) {
            val file = File(modelDir, name)
            if (file.exists() && file.length() > 0) return file
        }

        // Fallback: Check for any .gguf file present in the directory
        return modelDir.listFiles()?.firstOrNull { it.name.endsWith(".gguf") && it.length() > 0 }
    }

    fun getModelsHealth(): String {
        val brainFile = getActiveBrainModelFile()
        val hasBrain = brainFile != null
        val hasEars = earsModel.exists() && earsModel.length() > 0
        val hasVoice = voiceModel.exists() && voiceModel.length() > 0
        val hasVoiceData = voiceBin.exists() && voiceBin.length() > 0

        return buildString {
            append("⚡ JAI On-Device Engine Status:\n\n")
            if (hasBrain) {
                append("🧠 Brain (${brainFile?.name}): READY (${brainFile!!.length() / (1024 * 1024)} MB)\n")
            } else {
                append("❌ Brain: Missing .gguf model in /sdcard/jai_models/\n")
            }
            append(if (hasEars) "👂 Ears (Whisper): READY\n" else "⚪ Ears: Cloud STT Active\n")
            append(if (hasVoice && hasVoiceData) "🗣️ Voice (Kokoro): READY\n" else "⚪ Voice: Native Android TTS Active\n\n")

            if (hasBrain) {
                append("✅ Offline Brain Loaded & Ready!")
            } else {
                append("⚠️ Place your .gguf file in /sdcard/jai_models/")
            }
        }
    }
}

