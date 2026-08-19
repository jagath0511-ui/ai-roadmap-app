package com.jai.agent

import android.content.Context
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
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object AiScreenAnalyzer {

    // Paste your free Gemini API key here (from aistudio.google.com)
    var API_KEY = "YOUR_GEMINI_API_KEY"

    // Gemini Free Tier Daily Request Cap (250 RPD for Gemini 2.5 Flash)
    private const val DAILY_FREE_LIMIT = 250

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeWithHybridEngine(
        context: Context,
        bitmap: Bitmap?,
        customPrompt: String
    ): String = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("JAI_QUOTA_PREFS", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val savedDate = prefs.getString("LAST_DATE", "")
        var requestCount = prefs.getInt("DAILY_COUNT", 0)

        // Reset daily counter on a new day
        if (savedDate != today) {
            prefs.edit().putString("LAST_DATE", today).putInt("DAILY_COUNT", 0).apply()
            requestCount = 0
        }

        val hasValidKey = API_KEY.isNotBlank() && API_KEY != "YOUR_GEMINI_API_KEY"
        val isUnderDailyLimit = requestCount < DAILY_FREE_LIMIT

        // 1. Try Gemini Cloud first if key exists and under 250 daily limit
        if (hasValidKey && isUnderDailyLimit) {
            val cloudResponse = callGeminiCloud(bitmap, customPrompt)
            if (cloudResponse != null) {
                val newCount = requestCount + 1
                prefs.edit().putInt("DAILY_COUNT", newCount).apply()
                val remaining = DAILY_FREE_LIMIT - newCount
                return@withContext "⚡ [Gemini Cloud | $remaining free calls left today]\n\n$cloudResponse"
            }
        }

        // 2. Automatic Seamless Fallback to Gemma Local Brain
        val reason = when {
            !hasValidKey -> "No API Key"
            !isUnderDailyLimit -> "Daily Free Limit (250) Reached"
            else -> "Network Timeout"
        }

        val localResponse = LocalBrainEngine.generateOfflineResponse(customPrompt)
        return@withContext "🧠 [Gemma Local Brain | Fallback: $reason]\n\n$localResponse"
    }

    private fun callGeminiCloud(bitmap: Bitmap?, prompt: String): String? {
        try {
            val contentsArray = JSONArray()
            val partsArray = JSONArray()

            partsArray.put(JSONObject().put("text", prompt))

            if (bitmap != null) {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                val base64Image = Base64.getEncoder().encodeToString(stream.toByteArray())

                val inlineData = JSONObject().apply {
                    put("mime_type", "image/jpeg")
                    put("data", base64Image)
                }
                partsArray.put(JSONObject().put("inline_data", inlineData))
            }

            contentsArray.put(JSONObject().put("parts", partsArray))
            val jsonBody = JSONObject().put("contents", contentsArray)

            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$API_KEY"
            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(body)
                return json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            }
        } catch (ignored: Exception) {}
        return null
    }
}
