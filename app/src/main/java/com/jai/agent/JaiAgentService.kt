package com.jai.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JaiAgentService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("JaiAgentService", "JAI Accessibility Engine Connected & Ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 1. Track which app is currently open in the foreground
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty() && !pkg.contains("com.jai.agent")) {
                currentForegroundApp = pkg
            }
        }

        // 2. Automate WhatsApp typing & clicking Send button
        if (event.packageName == "com.whatsapp" && pendingWhatsAppMessage != null) {
            val root = rootInActiveWindow ?: return
            
            // Look for WhatsApp send button by view ID or content description
            val sendButtons = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
            if (!sendButtons.isNullOrEmpty()) {
                sendButtons[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                pendingWhatsAppMessage = null // Action complete
                Log.d("JaiAgentService", "WhatsApp message auto-sent successfully.")
            } else {
                // Fallback: look for "Send" button description
                val fallbackNodes = root.findAccessibilityNodeInfosByText("Send")
                if (!fallbackNodes.isNullOrEmpty()) {
                    fallbackNodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    pendingWhatsAppMessage = null
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w("JaiAgentService", "JAI Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    companion object {
        var instance: JaiAgentService? = null
        var currentForegroundApp: String = "Home Screen / Launcher"
        var pendingWhatsAppMessage: String? = null

        /**
         * Parses and executes OS actions returned by Gemini
         */
        fun executeCommand(context: Context, command: String): Boolean {
            val trimmed = command.trim()

            return when {
                // 1. Direct Phone Call
                trimmed.startsWith("ACTION:CALL:") -> {
                    val target = trimmed.removePrefix("ACTION:CALL:").trim()
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:$target")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    true
                }

                // 2. Automated WhatsApp Message
                trimmed.startsWith("ACTION:WHATSAPP:") -> {
                    val parts = trimmed.removePrefix("ACTION:WHATSAPP:").split(":", limit = 2)
                    val message = parts.lastOrNull()?.trim().orEmpty()
                    
                    pendingWhatsAppMessage = message
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        setPackage("com.whatsapp")
                        putExtra(Intent.EXTRA_TEXT, message)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    true
                }

                // 3. Set Alarm
                trimmed.startsWith("ACTION:ALARM:") -> {
                    val parts = trimmed.removePrefix("ACTION:ALARM:").split(":")
                    val hour = parts.getOrNull(0)?.toIntOrNull() ?: 7
                    val min = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    val label = parts.getOrNull(2) ?: "JAI Voice Alarm"

                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, min)
                        putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    true
                }

                // 4. Web Browsing / Google Search
                trimmed.startsWith("ACTION:BROWSE:") -> {
                    val query = trimmed.removePrefix("ACTION:BROWSE:").trim()
                    val url = if (query.startsWith("http://") || query.startsWith("https://")) {
                        query
                    } else {
                        "https://www.google.com/search?q=${Uri.encode(query)}"
                    }
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    true
                }

                // 5. Open Specific App by Name
                trimmed.startsWith("ACTION:OPEN:") -> {
                    val appName = trimmed.removePrefix("ACTION:OPEN:").trim().lowercase()
                    val pkg = when {
                        appName.contains("chrome") -> "com.android.chrome"
                        appName.contains("youtube") -> "com.google.android.youtube"
                        appName.contains("whatsapp") -> "com.whatsapp"
                        appName.contains("gmail") -> "com.google.android.gm"
                        appName.contains("maps") -> "com.google.android.apps.maps"
                        else -> null
                    }
                    if (pkg != null) {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) {
                            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(launchIntent)
                            return true
                        }
                    }
                    false
                }

                else -> false // Plain text conversation
            }
        }
    }
}

