package com.jai.agent

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager

object LocalBrainEngine {

    private var isTorchOn = false

    /**
     * Parses offline natural language commands and executes direct OS control actions.
     */
    fun processOfflineIntent(context: Context, rawPrompt: String): String? {
        val prompt = rawPrompt.lowercase().trim()

        return when {
            // 1. Flashlight / Torch Controls
            prompt.contains("flashlight on") || prompt.contains("turn on torch") || prompt.contains("torch on") -> {
                toggleFlashlight(context, true)
            }
            prompt.contains("flashlight off") || prompt.contains("turn off torch") || prompt.contains("torch off") -> {
                toggleFlashlight(context, false)
            }

            // 2. Volume & Audio Adjustments
            prompt.contains("volume up") || prompt.contains("increase volume") || prompt.contains("louder") -> {
                adjustVolume(context, AudioManager.ADJUST_RAISE)
            }
            prompt.contains("volume down") || prompt.contains("decrease volume") || prompt.contains("lower volume") -> {
                adjustVolume(context, AudioManager.ADJUST_LOWER)
            }
            prompt.contains("mute") || prompt.contains("silence phone") -> {
                setVolumeMute(context)
            }

            // 3. Battery & Power Status
            prompt.contains("battery") || prompt.contains("power level") || prompt.contains("juice left") -> {
                getBatteryStatus(context)
            }

            // 4. Quick Device Actions
            prompt.startsWith("open ") -> "ACTION:OPEN_APP:" + rawPrompt.substringAfter("open").trim()
            prompt.startsWith("call ") -> "ACTION:CALL:" + rawPrompt.substringAfter("call").trim()
            prompt.startsWith("type ") -> "ACTION:TYPE:" + rawPrompt.substringAfter("type").trim()
            prompt.startsWith("whatsapp ") -> "ACTION:WHATSAPP:" + rawPrompt.substringAfter("whatsapp").trim()
            prompt.startsWith("search ") || prompt.startsWith("browse ") -> {
                val query = rawPrompt.substringAfter("search").takeIf { it != rawPrompt } ?: rawPrompt.substringAfter("browse").trim()
                "ACTION:BROWSE:$query"
            }

            else -> null
        }
    }

    private fun toggleFlashlight(context: Context, state: Boolean): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return "No flashlight hardware detected."
            cameraManager.setTorchMode(cameraId, state)
            isTorchOn = state
            if (state) "Flashlight turned on." else "Flashlight turned off."
        } catch (e: Exception) {
            FailureLogger.log(context, "LocalBrainEngine:Torch", e.localizedMessage.orEmpty())
            "Unable to toggle flashlight."
        }
    }

    private fun adjustVolume(context: Context, direction: Int): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            "Volume adjusted."
        } catch (e: Exception) {
            FailureLogger.log(context, "LocalBrainEngine:Volume", e.localizedMessage.orEmpty())
            "Failed to adjust volume."
        }
    }

    private fun setVolumeMute(context: Context): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            "Audio muted."
        } catch (e: Exception) {
            FailureLogger.log(context, "LocalBrainEngine:Mute", e.localizedMessage.orEmpty())
            "Failed to mute audio."
        }
    }

    private fun getBatteryStatus(context: Context): String {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            "Battery is currently at $level percent."
        } catch (e: Exception) {
            FailureLogger.log(context, "LocalBrainEngine:Battery", e.localizedMessage.orEmpty())
            "Unable to read battery level."
        }
    }
}
