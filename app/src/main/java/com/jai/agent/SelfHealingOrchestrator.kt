package com.jai.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SelfHealingOrchestrator {

    var emergencyPhoneNumber: String = "+919876543210" // Replace with your target mobile number

    /**
     * Executes any code compilation or deployment task with an automatic 2-attempt self-healing retry loop.
     * Escalates to emergency voice call / WhatsApp if both attempts fail.
     */
    suspend fun executeWithSelfHealing(
        context: Context,
        taskName: String,
        taskAction: suspend (attempt: Int) -> Result<String>
    ): String = withContext(Dispatchers.IO) {
        var currentAttempt = 1
        var lastError = ""

        while (currentAttempt <= 2) {
            val result = taskAction(currentAttempt)
            if (result.isSuccess) {
                return@withContext result.getOrNull() ?: "Success"
            } else {
                lastError = result.exceptionOrNull()?.localizedMessage ?: "Unknown Error"
                FailureLogger.log(context, "SelfHealing:Attempt_$currentAttempt", lastError)

                // Request Gemini/Brain to diagnose and generate a patch diff
                val fixPrompt = """
                    CRITICAL BUG DETECTED during '$taskName' (Attempt $currentAttempt of 2).
                    Error Log: $lastError
                    Diagnose the issue and formulate the immediate code fix to recover.
                """.trimIndent()
                AiScreenAnalyzer.analyzeScreenImage(null, fixPrompt)

                currentAttempt++
            }
        }

        // Both attempts failed: Trigger Human Emergency Escalation Valve
        escalateToHuman(context, taskName, lastError)
        return@withContext "🚨 Escalated: Task '$taskName' failed after 2 self-healing attempts."
    }

    private fun escalateToHuman(context: Context, taskName: String, errorDetails: String) {
        // 1. WhatsApp Emergency Message Intent
        try {
            val waIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("[https://api.whatsapp.com/send?phone=$emergencyPhoneNumber&text=$](https://api.whatsapp.com/send?phone=$emergencyPhoneNumber&text=$){Uri.encode("🚨 JAI Emergency: Task '$taskName' failed after 2 retries.\nError: $errorDetails")}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(waIntent)
        } catch (ignored: Exception) {}

        // 2. Direct Phone Call Fallback
        try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$emergencyPhoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(callIntent)
        } catch (ignored: Exception) {}
    }
}

