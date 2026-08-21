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
        val appInstance = JaiAgentService.instance

        // 1. FAST PATH: Check on-device LocalBrainEngine first (sub-50ms execution, works offline)
        if (bitmap == null && appInstance != null) {
            val localResult = LocalBrainEngine.processOfflineIntent(appInstance, customPrompt)
            if (localResult != null) {
                return@withContext localResult
            }
        }

        // 2. CLOUD PATH: Route complex questions & visual reasoning to Gemini Flash-Lite
        try {
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
                    return@use "Brain connection offline. Try a basic command."
                }

                val jsonResponse = JSONObject(responseBody)
                val text = jsonResponse.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")

                text ?: "Command acknowledged."
            }
        } catch (e: Exception) {
            "Network unreachable. JAI local engine active."
        }
    }
}
