package com.jai.agent

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager

object LocalBrainEngine {

    fun processOfflineIntent(context: Context, rawPrompt: String): String? {
        val prompt = rawPrompt.lowercase().trim()
        val clean = prompt.replace("'", "")

        return when {
            // 1. WhatsApp Targeted Messaging (e.g., "send hi message to sai in whatsapp", "send hello to john", "msg sai hi")
            clean.contains("whatsapp") || clean.startsWith("send ") || clean.startsWith("msg ") || clean.startsWith("text ") -> {
                parseWhatsAppCommand(rawPrompt)
            }

            // 2. Open App and Type (e.g., "open gemini and type hello")
            (clean.startsWith("open ") || clean.startsWith("launch ")) && (clean.contains(" type ") || clean.contains(" write ")) -> {
                val appPart = clean.substringAfter("open ").substringAfter("launch ")
                    .substringBefore(" type ")
                    .substringBefore(" write ")
                    .replace("app", "").trim()
                val textPart = rawPrompt.substringAfter("type ")
                    .takeIf { it != rawPrompt } ?: rawPrompt.substringAfter("write ").trim()
                "ACTION:OPEN_AND_TYPE:$appPart:::$textPart"
            }

            // 3. Open App
            clean.startsWith("open ") || clean.startsWith("launch ") -> {
                val app = if (clean.startsWith("open ")) rawPrompt.substring(5).trim() else rawPrompt.substring(7).trim()
                "ACTION:OPEN_APP:$app"
            }

            // 4. Pure Text Injection
            clean.startsWith("type ") -> "ACTION:TYPE:" + rawPrompt.substring(5).trim()

            // 5. Phone Call
            clean.startsWith("call ") || clean.startsWith("dial ") -> {
                val target = rawPrompt.substringAfter(" ").trim()
                "ACTION:CALL:$target"
            }

            // 6. Device Hardware & Status
            clean.contains("flashlight on") || clean.contains("torch on") -> toggleFlashlight(context, true)
            clean.contains("flashlight off") || clean.contains("torch off") -> toggleFlashlight(context, false)
            clean.contains("volume up") || clean.contains("louder") -> adjustVolume(context, AudioManager.ADJUST_RAISE)
            clean.contains("volume down") || clean.contains("lower volume") -> adjustVolume(context, AudioManager.ADJUST_LOWER)
            clean.contains("mute") || clean.contains("silence") -> setVolumeMute(context)
            clean.contains("battery") || clean.contains("power level") -> getBatteryStatus(context)

            // 7. Search
            clean.startsWith("search ") || clean.startsWith("browse ") -> {
                val query = rawPrompt.substringAfter("search ").substringAfter("browse ").trim()
                "ACTION:BROWSE:$query"
            }

            else -> null
        }
    }

    private fun parseWhatsAppCommand(rawPrompt: String): String {
        var text = rawPrompt
            .replace("(?i)in whatsapp".toRegex(), "")
            .replace("(?i)on whatsapp".toRegex(), "")
            .replace("(?i)to whatsapp".toRegex(), "")
            .replace("(?i)via whatsapp".toRegex(), "")
            .replace("(?i)whatsapp".toRegex(), "")
            .trim()

        // Pattern: "send <message> to <contact>"
        if (text.contains(" to ", ignoreCase = true)) {
            val parts = text.split("(?i) to ".toRegex(), limit = 2)
            val msgRaw = parts[0]
                .replace("(?i)^send\\s+".toRegex(), "")
                .replace("(?i)^msg\\s+".toRegex(), "")
                .replace("(?i)^text\\s+".toRegex(), "")
                .replace("(?i)message".toRegex(), "")
                .trim()
            val contact = parts[1].trim()

            if (contact.isNotBlank() && msgRaw.isNotBlank()) {
                return "ACTION:WHATSAPP_TARGET:$contact:::$msgRaw"
            }
        }

        // Fallback: Generic WhatsApp send
        val fallbackMsg = text
            .replace("(?i)^send\\s+".toRegex(), "")
            .replace("(?i)^msg\\s+".toRegex(), "")
            .trim()
        return "ACTION:WHATSAPP:$fallbackMsg"
    }

    private fun toggleFlashlight(context: Context, state: Boolean): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return "No flashlight detected."
            cameraManager.setTorchMode(cameraId, state)
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
            "Battery is at $level percent."
        } catch (e: Exception) {
            "Unable to read battery level."
        }
    }
}

