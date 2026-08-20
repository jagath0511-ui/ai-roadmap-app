package com.jai.agent

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

    // Dedicated API Key
    var apiKey: String = "AQ.Ab8RN6L14sk4W0lOYZLLDdltA3U1FKHGML1h2asV1q-1SeXCHA"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeScreenImage(bitmap: Bitmap?, customPrompt: String): String = withContext(Dispatchers.IO) {
        try {
            val partsArray = JSONArray()
            partsArray.put(JSONObject().put("text", "Active App: ${JaiAgentService.currentForegroundApp}\nTask: $customPrompt"))

            // Compress and attach camera or screen bitmap only if present
            if (bitmap != null) {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 65, outputStream)
                val base64Data = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

                val inlineData = JSONObject().apply {
                    put("mimeType", "image/jpeg")
                    put("data", base64Data)
                }
                partsArray.put(JSONObject().put("inlineData", inlineData))
                outputStream.close()
            }

            val systemInstruction = JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                """
                You are JAI, an on-device Android Operating System Controller.
                Always output ONLY the exact action command tag:
                - Open app: ACTION:OPEN_APP:<app_name>
                - Type text: ACTION:TYPE:<text_to_type>
                - Send WhatsApp: ACTION:WHATSAPP:<message>
                - Call: ACTION:CALL:<contact_or_number>
                - Alarm/Schedule: ACTION:ALARM:<hour>:<minute>:<task_name>
                - Search: ACTION:BROWSE:<search_query>
                Provide concise direct answers for pure knowledge queries.
                """.trimIndent()
            )))

            val requestJson = JSONObject().apply {
                put("system_instruction", systemInstruction)
                put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))
            }

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = requestJson.toString().toRequestBody(mediaType)

            // Target active Gemini 3 Flash endpoint
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash:generateContent"
            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext fallbackLocalBrain(customPrompt)
                }

                val jsonResponse = JSONObject(responseBody)
                val text = jsonResponse.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")

                return@withContext text ?: fallbackLocalBrain(customPrompt)
            }
        } catch (e: Exception) {
            return@withContext fallbackLocalBrain(customPrompt)
        }
    }

    private fun fallbackLocalBrain(prompt: String): String {
        val lower = prompt.lowercase()
        return when {
            lower.contains("open") -> "ACTION:OPEN_APP:" + lower.substringAfter("open").trim()
            lower.contains("type") -> "ACTION:TYPE:" + lower.substringAfter("type").trim()
            lower.contains("call") -> "ACTION:CALL:" + lower.substringAfter("call").trim()
            lower.contains("alarm") || lower.contains("schedule") -> "ACTION:ALARM:7:00:Work Task"
            lower.contains("whatsapp") -> "ACTION:WHATSAPP:" + lower.substringAfter("whatsapp").trim()
            lower.contains("search") -> "ACTION:BROWSE:" + lower.substringAfter("search").trim()
            else -> "JAI Local Engine: Ready for voice and system commands."
        }
    }
}

