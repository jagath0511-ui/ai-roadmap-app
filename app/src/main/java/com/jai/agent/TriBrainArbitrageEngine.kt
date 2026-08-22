package com.jai.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class CodeCandidate(
    val modelName: String,
    val rawCode: String,
    val isValid: Boolean,
    val score: Int
)

object TriBrainArbitrageEngine {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * Executes the Tri-Brain Prompt Fan-Out across Gemini Flash, Claude/Qwen proxy,
     * and local rules simultaneously to benchmark the best code output.
     */
    suspend fun generateAndSelectBestCode(taskPrompt: String): String = coroutineScope {
        // 1. Fan-out requests in parallel coroutines
        val geminiDeferred = async(Dispatchers.IO) { queryGeminiCode(taskPrompt) }
        val secondaryDeferred = async(Dispatchers.IO) { querySecondaryBrainCode(taskPrompt) }

        val geminiCode = geminiDeferred.await()
        val secondaryCode = secondaryDeferred.await()

        // 2. Audit candidates for syntax completeness, structure, and tag validity
        val candidates = listOf(
            auditCandidate("Gemini-3.7-Flash", geminiCode),
            auditCandidate("Secondary-Brain", secondaryCode)
        )

        // 3. Select the highest-scoring candidate
        val winner = candidates.maxByOrNull { it.score }
        winner?.rawCode ?: geminiCode
    }

    private suspend fun queryGeminiCode(taskPrompt: String): String = withContext(Dispatchers.IO) {
        val prompt = """
            You are JAI Lead Engineer. Write clean, complete, production-ready code for:
            $taskPrompt
            Output ONLY valid raw code without conversational commentary.
        """.trimIndent()
        AiScreenAnalyzer.analyzeScreenImage(null, prompt)
            .replace("```html", "")
            .replace("```kotlin", "")
            .replace("```", "")
            .trim()
    }

    private suspend fun querySecondaryBrainCode(taskPrompt: String): String = withContext(Dispatchers.IO) {
        try {
            // Local mesh / proxy fallback attempt
            val prompt = "Generate structural code layout for: $taskPrompt"
            LocalBrainEngine.generateOfflineResponse(prompt)
        } catch (e: Exception) {
            ""
        }
    }

    private fun auditCandidate(model: String, code: String): CodeCandidate {
        if (code.isBlank()) return CodeCandidate(model, "", false, 0)

        var score = 10
        val isHtml = code.contains("<html", ignoreCase = true) || code.contains("<div", ignoreCase = true)
        val isKotlin = code.contains("class ") || code.contains("fun ")

        if (!isHtml && !isKotlin) score -= 5
        if (code.length < 50) score -= 4
        if (code.contains("TODO", ignoreCase = true)) score -= 2

        return CodeCandidate(model, code, score > 5, score)
    }
}

