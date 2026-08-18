package com.jai.agent

import android.content.Context
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

    // Enter your free Google Gemini API Key here
    private const val GEMINI_API_KEY = "YOUR_GEMINI_API_KEY"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeScreenImage(bitmap: Bitmap, userPrompt: String = "Explain what is on this screen simply, highlight key points, and solve any problems shown."): String {
        return withContext(Dispatchers.IO) {
            try {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

                val requestJson = JSONObject().apply {
                    val contents = JSONArray().apply {
                        val partObject = JSONObject().apply {
                            val parts = JSONArray().apply {
                                put(JSONObject().apply { put("text", userPrompt) })
                                put(JSONObject().apply {
                                    put("inline_data", JSONObject().apply {
                                        put("mime_type", "image/jpeg")
                                        put("data", base64Image)
                                    })
                                })
                            }
                            put("parts", parts)
                        }
                        put(partObject)
                    }
                    put("contents", contents)
                }

                val body = requestJson.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$GEMINI_API_KEY")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseString = response.body?.string() ?: ""
                
                val json = JSONObject(responseString)
                val text = json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                text
            } catch (e: Exception) {
                "Error analyzing screen: ${e.localizedMessage}"
            }
        }
    }
}

