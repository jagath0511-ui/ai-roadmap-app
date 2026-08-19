package com.jai.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

object BrowserAgent {

    suspend fun browseAndExtract(query: String): String = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"

            val document = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(12000)
                .get()

            val results = document.select(".result__snippet").eachText()
            if (results.isEmpty()) {
                "No instant search results found for '$query'."
            } else {
                buildString {
                    append("🌐 Search Findings for '$query':\n\n")
                    results.take(3).forEachIndexed { index, snippet ->
                        append("${index + 1}. $snippet\n\n")
                    }
                }
            }
        } catch (e: Exception) {
            "Web Agent Error: ${e.localizedMessage ?: "Connection timed out"}"
        }
    }
}
