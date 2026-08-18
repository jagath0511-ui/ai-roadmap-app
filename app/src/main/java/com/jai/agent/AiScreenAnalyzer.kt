package com.jai.agent

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

object AiScreenAnalyzer {

    private const val API_KEY = "YOUR_GEMINI_API_KEY"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeScreenImage(bitmap: Bitmap?, customPrompt: String): String {
        return withContext(Dispatchers.IO) {
            if (API_KEY == "YOUR_GEMINI_API_KEY") {
                return@withContext "⚡ JAI Demo Mode: Screen captured successfully.\n\nPrompt: $customPrompt\n\n(Add your Gemini API Key in AiScreenAnalyzer.kt to enable live neural analysis)."
            }

            try {
                val contentsArray = JSONArray()
                val partsArray = JSONArray()

                partsArray.put(JSONObject().put("text", customPrompt))

                if (bitmap != null) {
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    val base64Image = Base64.getEncoder().encodeToString(stream.toByteArray())

                    val inlineData = JSONObject().apply {
                        put("mime_type", "image/jpeg")
                        put("data", base64Image)
                    }
                    partsArray.put(JSONObject().put("inline_data", inlineData))
                }

                contentsArray.put(JSONObject().put("parts", partsArray))
                val jsonBody = JSONObject().put("contents", contentsArray)

                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$API_KEY"
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    json.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                } else {
                    "API Error (${response.code}): $responseBody"
                }
            } catch (e: Exception) {
                "Analysis Error: ${e.localizedMessage}"
            }
        }
    }
}
