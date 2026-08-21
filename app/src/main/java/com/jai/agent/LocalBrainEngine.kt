package com.jai.agent

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager

object LocalBrainEngine {

    private var isTorchOn = false

    fun processOfflineIntent(context: Context, rawPrompt: String): String? {
        val prompt = rawPrompt.lowercase().trim()
        val clean = prompt.replace("'", "")

        return when {
            // 1. Hardware Controls
            clean.contains("flashlight on") || clean.contains("turn on torch") || clean.contains("torch on") -> toggleFlashlight(context, true)
            clean.contains("flashlight off") || clean.contains("turn off torch") || clean.contains("torch off") -> toggleFlashlight(context, false)
            clean.contains("volume up") || clean.contains("increase volume") || clean.contains("louder") -> adjustVolume(context, AudioManager.ADJUST_RAISE)
            clean.contains("volume down") || clean.contains("decrease volume") || clean.contains("lower volume") -> adjustVolume(context, AudioManager.ADJUST_LOWER)
            clean.contains("mute") || clean.contains("silence phone") -> setVolumeMute(context)
            clean.contains("battery") || clean.contains("power level") || clean.contains("juice left") -> getBatteryStatus(context)

            // 2. Direct Phone Call Intent
            clean.startsWith("call ") || clean.startsWith("dial ") -> {
                val target = rawPrompt.substringAfter(" ").trim()
                "ACTION:CALL:$target"
            }

            // 3. Combined: Open App AND Type ("open gemini type hlo", "open chatgpt and write code")
            (clean.startsWith("open ") || clean.startsWith("launch ")) && (clean.contains(" type ") || clean.contains(" write ") || clean.contains(" and type ")) -> {
                val appPart = clean.substringAfter("open ").substringAfter("launch ")
                    .substringBefore(" type ")
                    .substringBefore(" write ")
                    .substringBefore(" and type ")
                    .trim()
                val textPart = rawPrompt.substringAfter("type ")
                    .takeIf { it != rawPrompt }
                    ?: rawPrompt.substringAfter("write ").trim()

                "ACTION:OPEN_AND_TYPE:$appPart:::$textPart"
            }

            // 4. WhatsApp Direct Actions ("send hi to sai on whatsapp", "whatsapp hello to mummy")
            clean.contains("whatsapp") && (clean.startsWith("send ") || clean.startsWith("write ") || clean.startsWith("message ")) -> {
                val message = rawPrompt.replace("send", "", ignoreCase = true)
                    .replace("in whatsapp", "", ignoreCase = true)
                    .replace("on whatsapp", "", ignoreCase = true)
                    .replace("to whatsapp", "", ignoreCase = true)
                    .replace("whatsapp", "", ignoreCase = true)
                    .trim()
                "ACTION:WHATSAPP:$message"
            }

            // 5. Gmail / Email Intent ("send email hello", "mail I will be late")
            clean.startsWith("email ") || clean.startsWith("mail ") || clean.startsWith("send email ") || clean.startsWith("send mail ") -> {
                val body = rawPrompt.substringAfter("mail ").substringAfter("email ").trim()
                "ACTION:GMAIL:$body"
            }

            // 6. SMS / Default Messages Intent ("message mummy coming home", "sms 98765 hello")
            clean.startsWith("sms ") || clean.startsWith("text ") || (clean.startsWith("message ") && !clean.contains("whatsapp")) -> {
                val text = rawPrompt.substringAfter(" ").trim()
                "ACTION:SMS:$text"
            }

            // 7. Instagram Intent ("open instagram", "instagram")
            clean == "instagram" || clean == "open instagram" -> "ACTION:OPEN_APP:instagram"

            // 8. General App Open
            clean.startsWith("open ") -> "ACTION:OPEN_APP:" + rawPrompt.substringAfter("open").trim()

            // 9. Pure Type
            clean.startsWith("type ") -> "ACTION:TYPE:" + rawPrompt.substringAfter("type").trim()

            // 10. Web / Search
            clean.startsWith("search ") || clean.startsWith("browse ") -> {
                val query = rawPrompt.substringAfter("search").takeIf { it != rawPrompt } ?: rawPrompt.substringAfter("browse").trim()
                "ACTION:BROWSE:$query"
            }

            else -> null
        }
    }

    private fun toggleFlashlight(context: Context, state: Boolean): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return "No flashlight detected."
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
            "Battery is at $level percent."
        } catch (e: Exception) {
            "Unable to read battery level."
        }
    }
}
