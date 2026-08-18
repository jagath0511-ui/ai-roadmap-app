package com.jai.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

object BrowserAgent {

    suspend fun browseAndExtract(query: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val searchUrl = "https://html.duckduckgo.com/html/?q=$encodedQuery"
                
                val doc = Jsoup.connect(searchUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(10000)
                    .get()

                val snippets = StringBuilder()
                val results = doc.select(".result__snippet")
                for (i in 0 until Math.min(5, results.size)) {
                    snippets.append("${i + 1}. ").append(results[i].text()).append("\n\n")
                }

                if (snippets.isEmpty()) {
                    return@withContext "Browser Agent: No live web results found for '$query'."
                }

                snippets.toString().trim()
            } catch (e: Exception) {
                "Browser Agent Error: ${e.localizedMessage}"
            }
        }
    }
}

