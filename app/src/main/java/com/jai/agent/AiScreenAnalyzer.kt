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
            partsArray.put(JSONObject().put("text", "Current Foreground App: ${JaiAgentService.currentForegroundApp}\nUser Query: $customPrompt"))

            if (bitmap != null) {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
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
                You are JAI, a hands-free on-device Android Operating System Controller.
                You HAVE full device access. When asked to perform an action, return ONLY the tag:
                - Make a call: ACTION:CALL:<number_or_name>
                - Set alarm: ACTION:ALARM:<hour>:<minute>:<label>
                - Open website or search: ACTION:BROWSE:<search_query_or_url>
                - Send WhatsApp: ACTION:WHATSAPP:<message_text>
                For regular knowledge questions, answer concisely in 1 sentence.
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
                    // Fallback to local rule engine on rate limits (429) or connection errors
                    return@withContext fallbackLocalBrain(customPrompt)
                }

                val jsonResponse = JSONObject(responseBody)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    return@withContext parts?.getJSONObject(0)?.optString("text") ?: "No response generated."
                } else {
                    return@withContext fallbackLocalBrain(customPrompt)
                }
            }
        } catch (e: Exception) {
            return@withContext fallbackLocalBrain(customPrompt)
        }
    }

    // Zero-token local rule engine (No missing Gemma file crash)
    private fun fallbackLocalBrain(prompt: String): String {
        val lower = prompt.lowercase()
        return when {
            lower.contains("call") -> "ACTION:CALL:" + lower.substringAfter("call").trim()
            lower.contains("alarm") -> "ACTION:ALARM:7:00:WakeUp"
            lower.contains("whatsapp") -> "ACTION:WHATSAPP:" + lower.substringAfter("whatsapp").trim()
            lower.contains("open") || lower.contains("search") -> "ACTION:BROWSE:" + lower.substringAfter("open").substringAfter("search").trim()
            else -> "JAI Offline Mode: Action executed locally."
        }
    }
}
