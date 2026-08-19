package com.jai.agent

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object DeployerEngine {

    private const val GITHUB_OWNER = "jagath0511-ui"
    private const val GITHUB_REPO = "ai-roadmap-app"
    private const val GITHUB_BRANCH = "main"
    
    // GitHub Classic Personal Access Token
    var githubToken: String = "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"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun commitAndDeployFile(filePath: String, fileContent: String, commitMessage: String): String = withContext(Dispatchers.IO) {
        try {
            val getUrl = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/contents/$filePath?ref=$GITHUB_BRANCH"
            val getRequest = Request.Builder()
                .url(getUrl)
                .addHeader("Authorization", "Bearer $githubToken")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2022-11-28")
                .get()
                .build()

            var existingSha: String? = null
            client.newCall(getRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    existingSha = JSONObject(body).optString("sha")
                }
            }

            val encodedContent = Base64.encodeToString(fileContent.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val putPayload = JSONObject().apply {
                put("message", commitMessage)
                put("content", encodedContent)
                put("branch", GITHUB_BRANCH)
                if (!existingSha.isNullOrEmpty()) {
                    put("sha", existingSha)
                }
            }

            val putUrl = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/contents/$filePath"
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val putBody = putPayload.toString().toRequestBody(mediaType)

            val putRequest = Request.Builder()
                .url(putUrl)
                .addHeader("Authorization", "Bearer $githubToken")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2022-11-28")
                .put(putBody)
                .build()

            client.newCall(putRequest).execute().use { putResponse ->
                val resBody = putResponse.body?.string().orEmpty()
                if (putResponse.isSuccessful) {
                    "✅ Successfully pushed '$filePath' to $GITHUB_BRANCH. CI/CD build triggered."
                } else {
                    "❌ GitHub Deploy Error (${putResponse.code}): $resBody"
                }
            }
        } catch (e: Exception) {
            "Deployer Exception: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }
    }
}

