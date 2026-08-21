package com.jai.agent

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager

object LocalBrainEngine {

    private var isTorchOn = false

    fun processOfflineIntent(context: Context, rawPrompt: String): String? {
        val prompt = rawPrompt.lowercase().trim()
        val normalized = prompt.replace("'", "")

        return when {
            // 1. Flashlight / Torch
            normalized.contains("flashlight on") || normalized.contains("turn on torch") || normalized.contains("torch on") -> {
                toggleFlashlight(context, true)
            }
            normalized.contains("flashlight off") || normalized.contains("turn off torch") || normalized.contains("torch off") -> {
                toggleFlashlight(context, false)
            }

            // 2. Volume
            normalized.contains("volume up") || normalized.contains("increase volume") || normalized.contains("louder") -> {
                adjustVolume(context, AudioManager.ADJUST_RAISE)
            }
            normalized.contains("volume down") || normalized.contains("decrease volume") || normalized.contains("lower volume") -> {
                adjustVolume(context, AudioManager.ADJUST_LOWER)
            }
            normalized.contains("mute") || normalized.contains("silence phone") -> {
                setVolumeMute(context)
            }

            // 3. Battery
            normalized.contains("battery") || normalized.contains("power level") || normalized.contains("juice left") -> {
                getBatteryStatus(context)
            }

            // 4. Flexible App Launching ("open whats app", "open gemini app", etc.)
            normalized.startsWith("open ") -> {
                val targetApp = rawPrompt.substringAfter("open").trim()
                "ACTION:OPEN_APP:$targetApp"
            }

            // 5. WhatsApp Intent Parsing
            normalized.contains("whatsapp") && (normalized.startsWith("send ") || normalized.startsWith("write ")) -> {
                val message = rawPrompt.replace("send", "", ignoreCase = true)
                    .replace("in whatsapp", "", ignoreCase = true)
                    .replace("on whatsapp", "", ignoreCase = true)
                    .replace("whatsapp", "", ignoreCase = true)
                    .trim()
                "ACTION:WHATSAPP:$message"
            }

            normalized.startsWith("call ") -> "ACTION:CALL:" + rawPrompt.substringAfter("call").trim()
            normalized.startsWith("type ") -> "ACTION:TYPE:" + rawPrompt.substringAfter("type").trim()
            normalized.startsWith("search ") || normalized.startsWith("browse ") -> {
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
            "Unable to toggle flashlight."
        }
    }

    private fun adjustVolume(context: Context, direction: Int): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            "Volume adjusted."
        } catch (e: Exception) {
            "Failed to adjust volume."
        }
    }

    private fun setVolumeMute(context: Context): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            "Audio muted."
        } catch (e: Exception) {
            "Failed to mute audio."
        }
    }

    private fun getBatteryStatus(context: Context): String {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            "Battery is currently at $level percent."
        } catch (e: Exception) {
            "Unable to read battery level."
        }
    }
}
