package com.jai.agent

import android.accessibilityservice.AccessibilityService
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

        // 1. Foreground App Detection
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty() && !pkg.contains("com.jai.agent")) {
                currentForegroundApp = pkg
            }
        }

        val rootNode = rootInActiveWindow ?: return

        // 2. Generic Input Field Typing (WhatsApp, Claude, Gemini, Notes)
        if (!pendingTextToType.isNullOrBlank()) {
            val focusNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusNode != null && focusNode.isEditable) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pendingTextToType)
                }
                focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                pendingTextToType = null
            }
        }

        // 3. Automated WhatsApp Send Execution
        if (event.packageName == "com.whatsapp" && pendingWhatsAppMessage != null) {
            val sendButtons = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
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
        var pendingTextToType: String? = null
        var pendingWhatsAppMessage: String? = null

        fun executeCommand(context: Context, responseText: String): Boolean {
            val trimmed = responseText.trim()

            return when {
                // 1. OPEN APPS
                trimmed.startsWith("ACTION:OPEN_APP:") -> {
                    val appName = trimmed.removePrefix("ACTION:OPEN_APP:").trim().lowercase()
                    val pkg = when {
                        appName.contains("whatsapp") -> "com.whatsapp"
                        appName.contains("claude") -> "com.anthropic.claude"
                        appName.contains("gemini") -> "com.google.android.apps.bard"
                        appName.contains("youtube") -> "com.google.android.youtube"
                        appName.contains("camera") -> "com.android.camera"
                        appName.contains("gmail") || appName.contains("email") -> "com.google.android.gm"
                        appName.contains("chrome") -> "com.android.chrome"
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
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(appName)}")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    true
                }

                // 2. TYPE TEXT INTO CURRENT ACTIVE INPUT
                trimmed.startsWith("ACTION:TYPE:") -> {
                    val text = trimmed.removePrefix("ACTION:TYPE:").trim()
                    pendingTextToType = text
                    val focusNode = instance?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focusNode != null && focusNode.isEditable) {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                        }
                        focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        pendingTextToType = null
                    }
                    true
                }

                // 3. WHATSAPP AUTO DISPATCH
                trimmed.startsWith("ACTION:WHATSAPP:") -> {
                    val msg = trimmed.removePrefix("ACTION:WHATSAPP:").trim()
                    pendingWhatsAppMessage = msg
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        setPackage("com.whatsapp")
                        putExtra(Intent.EXTRA_TEXT, msg)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    true
                }

                // 4. PHONE CALLS
                trimmed.startsWith("ACTION:CALL:") -> {
                    val target = trimmed.removePrefix("ACTION:CALL:").trim()
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$target")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    true
                }

                // 5. ALARMS & SCHEDULES
                trimmed.startsWith("ACTION:ALARM:") -> {
                    val parts = trimmed.removePrefix("ACTION:ALARM:").split(":")
                    val hour = parts.getOrNull(0)?.toIntOrNull() ?: 7
                    val min = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    val taskName = parts.getOrNull(2) ?: "JAI Work Schedule"

                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, min)
                        putExtra(AlarmClock.EXTRA_MESSAGE, taskName)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    true
                }

                // 6. WEB BROWSER SEARCH
                trimmed.startsWith("ACTION:BROWSE:") -> {
                    val query = trimmed.removePrefix("ACTION:BROWSE:").trim()
                    val url = if (query.startsWith("http")) query else "https://www.google.com/search?q=${Uri.encode(query)}"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    true
                }

                else -> false
            }
        }
    }
}

