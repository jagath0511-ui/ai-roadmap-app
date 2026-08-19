package com.jai.agent

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object AiScreenAnalyzer {

    // Your Google AI Studio Gemini API Key
    private const val API_KEY = "AQ.Ab8RN6LGzp4hSH577WHPLJscu4hWr8jPx-VWolSgZSv3xm8kRg"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeScreenImage(bitmap: Bitmap?, customPrompt: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val contentsArray = JSONArray()
                val partsArray = JSONArray()

                // Add text instruction
                partsArray.put(JSONObject().put("text", customPrompt))

                // Add image payload
                if (bitmap != null) {
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    val base64Image = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

                    val inlineData = JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", base64Image)
                    }
                    partsArray.put(JSONObject().put("inlineData", inlineData))
                }

                contentsArray.put(JSONObject().put("parts", partsArray))
                val jsonBody = JSONObject().put("contents", contentsArray)

                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
                
                val request = Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", API_KEY)
                    .addHeader("Content-Type", "application/json")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val content = candidates.getJSONObject(0).optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            parts.getJSONObject(0).optString("text", "No text generated.")
                        } else {
                            "Response completed with no text (Finish reason: ${candidates.getJSONObject(0).optString("finishReason")})"
                        }
                    } else {
                        "No response candidate returned by Gemini."
                    }
                } else {
                    "API Error (${response.code}): $responseBody"
                }
            } catch (e: Exception) {
                "Vision Analysis Error: ${e.localizedMessage}"
            }
        }
    }
}
