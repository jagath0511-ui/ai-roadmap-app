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
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeScreenImage(bitmap: Bitmap?, customPrompt: String): String = withContext(Dispatchers.IO) {
        try {
            // PHASE 2 SPEED UPGRADE:
            // If no camera bitmap is attached (meaning no live vision/screen capture analysis is required)
            // and the user prompt is a simple text command, skip the cloud round-trip entirely!
            // This gives us instant sub-1.5s local execution for everyday actions.
            if (bitmap == null && isSimpleCommand(customPrompt)) {
                return@withContext executeLocalCommand(customPrompt)
            }

            val partsArray = JSONArray()
            partsArray.put(JSONObject().put("text", "Active App: ${JaiAgentService.currentForegroundApp}\nTask: $customPrompt"))

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
            }

            val systemInstruction = JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                """
                You are JAI, an ultra-fast on-device Android OS Agent.
                Trigger phone actions using ONLY these prefixes:
                - Open app: ACTION:OPEN_APP:<app_name>
                - Type text into focused field: ACTION:TYPE:<text_to_type>
                - Send WhatsApp message: ACTION:WHATSAPP:<message>
                - Phone call: ACTION:CALL:<contact_or_number>
                - Alarm/Schedule: ACTION:ALARM:<hour>:<minute>:<label>
                - Web search: ACTION:BROWSE:<search_query>
                For pure conversational questions, reply in 1 concise sentence.
                """.trimIndent()
            )))

            val requestJson = JSONObject().apply {
                put("system_instruction", systemInstruction)
                put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))
            }

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = requestJson.toString().toRequestBody(mediaType)

            // Target Gemini 3.1 Flash-Lite Endpoint for vision and complex reasoning
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent"
            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext executeLocalCommand(customPrompt)
                }

                val jsonResponse = JSONObject(responseBody)
                val text = jsonResponse.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")

                return@withContext text ?: executeLocalCommand(customPrompt)
            }
        } catch (e: Exception) {
            return@withContext executeLocalCommand(customPrompt)
        }
    }

    private fun isSimpleCommand(prompt: String): Boolean {
        val lower = prompt.lowercase().trim()
        return lower.startsWith("open") ||
               lower.startsWith("type") ||
               lower.startsWith("call") ||
               lower.startsWith("alarm") ||
               lower.startsWith("schedule") ||
               lower.startsWith("whatsapp") ||
               lower.startsWith("search") ||
               lower.startsWith("browse")
    }

    private fun executeLocalCommand(prompt: String): String {
        val lower = prompt.lowercase().trim()
        return when {
            lower.startsWith("open") -> "ACTION:OPEN_APP:" + prompt.substringAfter("open").trim()
            lower.startsWith("type") -> "ACTION:TYPE:" + prompt.substringAfter("type").trim()
            lower.startsWith("call") -> "ACTION:CALL:" + prompt.substringAfter("call").trim()
            lower.startsWith("alarm") || lower.startsWith("schedule") -> "ACTION:ALARM:7:00:Work Task"
            lower.startsWith("whatsapp") -> "ACTION:WHATSAPP:" + prompt.substringAfter("whatsapp").trim()
            lower.startsWith("search") || lower.startsWith("browse") -> {
                val query = prompt.substringAfter("search").takeIf { it != prompt } ?: prompt.substringAfter("browse").trim()
                "ACTION:BROWSE:$query"
            }
            else -> "JAI local engine active. Ready for your command."
        }
    }
}
