package com.jai.agent

import android.view.accessibility.AccessibilityNodeInfo

enum class ContentCategory {
    SCAM_RISK,
    PROMOTION,
    CLEAN_INFO
}

data class FilterAnalysisResult(
    val category: ContentCategory,
    val headline: String,
    val extractedSummary: String
)

object ScreenFilterEngine {

    // Common promotional triggers (Schools, Colleges, Courses, E-commerce, Marketing)
    private val promoKeywords = listOf(
        "admission open", "admissions open", "enroll now", "enrol now",
        "scholarship", "entrance exam", "free webinar", "free masterclass",
        "flat % off", "% off", "limited offer", "limited period offer",
        "buy 1 get 1", "use coupon", "use promo code", "sponsored",
        "exclusive discount", "book a free demo", "registration open",
        "upskill now", "apply today", "join now for free"
    )

    // Common scam & fraud triggers
    private val scamKeywords = listOf(
        "share otp", "enter your pin", "account suspended", "kyc suspended",
        "update kyc", "bank account blocked", "won lottery", "claim cash prize",
        "tax refund pending", "unauthorized transaction alert", "send money to claim"
    )

    /**
     * Traverses the active accessibility node tree and gathers all visible text on the screen.
     */
    fun extractScreenText(rootNode: AccessibilityNodeInfo?): String {
        if (rootNode == null) return ""
        val textBuilder = StringBuilder()
        collectTextRecursive(rootNode, textBuilder)
        return textBuilder.toString().trim()
    }

    private fun collectTextRecursive(node: AccessibilityNodeInfo, builder: StringBuilder) {
        node.text?.let {
            if (it.isNotBlank()) builder.append(it).append("\n")
        }
        node.contentDescription?.let {
            if (it.isNotBlank()) builder.append(it).append("\n")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextRecursive(child, builder)
            child.recycle()
        }
    }

    /**
     * Analyzes raw screen text to detect scams, college/school promotions, and clean content.
     */
    fun evaluateScreenContent(rawText: String): FilterAnalysisResult {
        val lower = rawText.lowercase()

        // 1. Check for High-Risk Phishing/Scams
        for (scam in scamKeywords) {
            if (lower.contains(scam)) {
                return FilterAnalysisResult(
                    category = ContentCategory.SCAM_RISK,
                    headline = "High-Risk Alert",
                    extractedSummary = "Caution: Detected potential scam or sensitive request ($scam)."
                )
            }
        }

        // 2. Check for Promotional Noise (Colleges, Courses, Website Ads)
        for (promo in promoKeywords) {
            if (lower.contains(promo)) {
                return FilterAnalysisResult(
                    category = ContentCategory.PROMOTION,
                    headline = "Promotional Content",
                    extractedSummary = "Promotional ad detected ($promo). Filtered out."
                )
            }
        }

        // 3. Clean Informational Content
        val lines = rawText.lines().filter { it.isNotBlank() }
        val cleanPreview = lines.take(3).joinToString(" ")
        return FilterAnalysisResult(
            category = ContentCategory.CLEAN_INFO,
            headline = "Clean Content",
            extractedSummary = cleanPreview.ifEmpty { "No readable text on active screen." }
        )
    }
}
