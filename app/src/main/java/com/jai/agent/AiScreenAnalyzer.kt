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

    var apiKey: String = "AQ.Ab8RN6L14sk4W0lOYZLLDdltA3U1FKHGML1h2asV1q-1SeXCHA"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeScreenImage(bitmap: Bitmap?, customPrompt: String): String = withContext(Dispatchers.IO) {
        try {
            val partsArray = JSONArray()
            partsArray.put(JSONObject().put("text", "Current Foreground App: ${JaiAgentService.currentForegroundApp}\nUser Request: $customPrompt"))

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

            // ENFORCED SYSTEM INSTRUCTION FOR OS AGENT CONTROL
            val systemInstruction = JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                """
                You are JAI, a hands-free OS controller for Android.
                Always output ONLY the correct action command tag when asked to perform a task:
                - Open app: ACTION:OPEN_APP:<appName> (e.g., whatsapp, claude, gemini, camera, youtube)
                - Type text: ACTION:TYPE:<text_to_type>
                - Phone Call: ACTION:CALL:<contact_or_number>
                - Alarm/Schedule: ACTION:ALARM:<hour>:<minute>:<task_name>
                - Send WhatsApp: ACTION:WHATSAPP:<message>
                - Send Email: ACTION:EMAIL:<email_body>
                - Web Search: ACTION:BROWSE:<search_query>
                Only give conversational text answers for pure knowledge questions.
                """.trimIndent()
            )))

            val requestJson = JSONObject().apply {
                put("system_instruction", systemInstruction)
                put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))
            }

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = requestJson.toString().toRequestBody(mediaType)

            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
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

    // Precise Local Brain Fallback
    fun fallbackLocalBrain(prompt: String): String {
        val lower = prompt.lowercase()
        return when {
            lower.contains("open") -> {
                val app = lower.substringAfter("open").trim()
                "ACTION:OPEN_APP:$app"
            }
            lower.contains("type") -> {
                val text = lower.substringAfter("type").trim()
                "ACTION:TYPE:$text"
            }
            lower.contains("call") -> "ACTION:CALL:" + lower.substringAfter("call").trim()
            lower.contains("alarm") || lower.contains("schedule") || lower.contains("remind") -> "ACTION:ALARM:7:00:Work Schedule"
            lower.contains("whatsapp") -> "ACTION:WHATSAPP:" + lower.substringAfter("whatsapp").trim()
            lower.contains("search") -> "ACTION:BROWSE:" + lower.substringAfter("search").trim()
            else -> "I am ready. Tell me to open apps, type messages, make calls, or schedule reminders."
        }
    }
}
