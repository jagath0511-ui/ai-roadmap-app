package com.jai.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JaiAgentService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 1. Track current open app
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty() && !pkg.contains("com.jai.agent")) {
                currentForegroundApp = pkg
            }
        }

        // 2. Automated WhatsApp send execution
        if (event.packageName == "com.whatsapp" && pendingWhatsAppMessage != null) {
            val root = rootInActiveWindow ?: return
            val sendButtons = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
            if (!sendButtons.isNullOrEmpty()) {
                sendButtons[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                pendingWhatsAppMessage = null
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    companion object {
        var instance: JaiAgentService? = null
        var currentForegroundApp: String = "Launcher / Home"
        var pendingWhatsAppMessage: String? = null

        fun executeCommand(context: Context, responseText: String): Boolean {
            val trimmed = responseText.trim()

            return when {
                // 1. Make Phone Call
                trimmed.startsWith("ACTION:CALL:") -> {
                    val target = trimmed.removePrefix("ACTION:CALL:").trim()
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$target")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    true
                }

                // 2. Set Alarm
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

                // 3. Web Browsing / Google Search
                trimmed.startsWith("ACTION:BROWSE:") -> {
                    val query = trimmed.removePrefix("ACTION:BROWSE:").trim()
                    val url = if (query.startsWith("http")) query else "https://www.google.com/search?q=${Uri.encode(query)}"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    true
                }

                // 4. WhatsApp Automation
                trimmed.startsWith("ACTION:WHATSAPP:") -> {
                    val message = trimmed.removePrefix("ACTION:WHATSAPP:").trim()
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

                else -> false // Plain conversational response
            }
        }
    }
}
