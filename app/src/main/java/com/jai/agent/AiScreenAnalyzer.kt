package com.jai.agent

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object AiScreenAnalyzer {

    var apiKey: String = "AQ.Ab8RN6L14sk4W0lOYZLLDdltA3U1FKHGML1h2asV1q-1SeXCHA"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeScreenImage(bitmap: Bitmap?, customPrompt: String): String = withContext(Dispatchers.IO) {
        try {
            val partsArray = JSONArray()
            partsArray.put(JSONObject().put("text", customPrompt))

            // Add compressed screenshot only when requested
            if (bitmap != null) {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
                val base64Data = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

                val inlineData = JSONObject().apply {
                    put("mimeType", "image/jpeg")
                    put("data", base64Data)
                }
                partsArray.put(JSONObject().put("inlineData", inlineData))
                outputStream.close()
                bitmap.recycle() // Release memory immediately for battery preservation
            }

            // System prompt forcing hardware action tags
            val systemInstruction = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", 
                """
                You are JAI, an Android OS Agent. Trigger native actions using ONLY these prefixes:
                - ACTION:CALL:<contact_or_number>
                - ACTION:ALARM:<hour>:<minute>:<label>
                - ACTION:BROWSE:<query_or_url>
                - ACTION:WHATSAPP:<contact>:<message>
                For regular answers, reply concisely in one sentence.
                """.trimIndent()
            )))

            val requestJson = JSONObject().apply {
                put("system_instruction", systemInstruction)
                put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))
            }

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = requestJson.toString().toRequestBody(mediaType)

            // Current production endpoint
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()

                // Fallback to local regex brain if quota runs out (HTTP 429)
                if (!response.isSuccessful) {
                    if (response.code == 429 || response.code == 503) {
                        return@withContext fallbackLocalBrain(customPrompt)
                    }
                    return@withContext "API Error (${response.code})"
                }

                val jsonResponse = JSONObject(responseBody)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    return@withContext parts?.getJSONObject(0)?.optString("text") ?: "No output"
                } else {
                    return@withContext fallbackLocalBrain(customPrompt)
                }
            }
        } catch (e: Exception) {
            return@withContext fallbackLocalBrain(customPrompt)
        }
    }

    // Pure offline fallback - eliminates missing Gemma errors
    fun fallbackLocalBrain(prompt: String): String {
        val lower = prompt.lowercase()
        return when {
            lower.contains("call") -> "ACTION:CALL:${lower.substringAfter("call").trim()}"
            lower.contains("alarm") -> "ACTION:ALARM:7:00:Alarm"
            lower.contains("whatsapp") || lower.contains("message") -> {
                val text = lower.substringAfter("message").substringAfter("to").trim()
                "ACTION:WHATSAPP:$text"
            }
            lower.contains("open") || lower.contains("search") -> {
                val target = lower.substringAfter("open").substringAfter("search").trim()
                "ACTION:BROWSE:$target"
            }
            else -> "Offline Brain active: Ready for calls, alarms, and browser commands."
        }
    }
}
