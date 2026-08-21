package com.jai.agent

import android.content.Context
import android.content.Intent
import android.net.Uri

object BrowserAgent {

    /**
     * Intelligently routes queries based on intent (Navigation, Video, Direct URL, or Web Search).
     */
    fun routeWebIntent(context: Context, queryRaw: String): ActionResult {
        val query = queryRaw.trim()
        if (query.isBlank()) {
            val reason = "Empty search query"
            FailureLogger.log(context, "BrowserAgent", reason)
            return ActionResult.Failure(reason)
        }

        val lower = query.lowercase()

        return when {
            // 1. Direct Website URL
            lower.startsWith("http://") || lower.startsWith("https://") || lower.endsWith(".com") || lower.endsWith(".org") || lower.endsWith(".io") -> {
                val formattedUrl = if (!query.startsWith("http")) "https://$query" else query
                launchUrl(context, formattedUrl, "Opening website: $query")
            }

            // 2. Maps & Navigation Intent
            lower.startsWith("navigate to") || lower.startsWith("directions to") || lower.startsWith("route to") -> {
                val destination = query.substringAfter("to").trim()
                val mapUri = Uri.parse("google.navigation:q=${Uri.encode(destination)}")
                val mapIntent = Intent(Intent.ACTION_VIEW, mapUri).apply {
                    setPackage("com.google.android.apps.maps")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    context.startActivity(mapIntent)
                    ActionResult.Success("Starting navigation to $destination.")
                } catch (e: Exception) {
                    launchUrl(context, "https://www.google.com/maps/search/${Uri.encode(destination)}", "Searching maps for $destination")
                }
            }

            // 3. YouTube / Video Search Intent
            lower.startsWith("play ") || lower.startsWith("watch ") || lower.contains("video of") -> {
                val videoQuery = query.replace("play", "").replace("watch", "").trim()
                val youtubeIntent = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", videoQuery)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    context.startActivity(youtubeIntent)
                    ActionResult.Success("Searching YouTube for $videoQuery.")
                } catch (e: Exception) {
                    launchUrl(context, "https://www.youtube.com/results?search_query=${Uri.encode(videoQuery)}", "Searching YouTube for $videoQuery")
                }
            }

            // 4. Default Smart Google Search
            else -> {
                val searchUrl = "https://www.google.com/search?q=${Uri.encode(query)}"
                launchUrl(context, searchUrl, "Searching web for $query")
            }
        }
    }

    private fun launchUrl(context: Context, url: String, successMessage: String): ActionResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ActionResult.Success(successMessage)
        } catch (e: Exception) {
            val reason = "Could not launch web browser: ${e.localizedMessage}"
            FailureLogger.log(context, "BrowserAgent", reason)
            ActionResult.Failure(reason)
        }
    }
}
