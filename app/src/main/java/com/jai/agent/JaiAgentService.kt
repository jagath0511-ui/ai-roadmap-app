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

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty() && !pkg.contains("com.jai.agent")) {
                currentForegroundApp = pkg
            }
        }

        val rootNode = rootInActiveWindow ?: return

        // 1. Text Injection
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

        // 2. Resilient WhatsApp Auto-Send (ID + Content Description Fallback)
        if (event.packageName == "com.whatsapp" && pendingWhatsAppMessage != null) {
            var sendBtn = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")?.firstOrNull()
            
            if (sendBtn == null) {
                sendBtn = rootNode.findAccessibilityNodeInfosByText("Send")?.firstOrNull()
            }
            if (sendBtn == null) {
                sendBtn = findNodeByContentDescription(rootNode, "Send")
            }

            if (sendBtn != null && sendBtn.isClickable) {
                sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                pendingWhatsAppMessage = null
            }
        }
    }

    private fun findNodeByContentDescription(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString().equals(text, ignoreCase = true)) {
            return root
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByContentDescription(child, text)
            if (found != null) return found
        }
        return null
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
                trimmed.startsWith("ACTION:OPEN_APP:") -> {
                    val appName = trimmed.removePrefix("ACTION:OPEN_APP:").trim().lowercase()
                    val pkgs = when {
                        appName.contains("whatsapp") -> listOf("com.whatsapp")
                        appName.contains("claude") -> listOf("com.anthropic.claude")
                        appName.contains("gemini") -> listOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox")
                        appName.contains("youtube") -> listOf("com.google.android.youtube")
                        appName.contains("camera") -> listOf("com.android.camera", "com.google.android.GoogleCamera")
                        appName.contains("gmail") || appName.contains("email") -> listOf("com.google.android.gm")
                        appName.contains("chrome") -> listOf("com.android.chrome")
                        else -> emptyList()
                    }

                    for (pkg in pkgs) {
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

                trimmed.startsWith("ACTION:CALL:") -> {
                    val target = trimmed.removePrefix("ACTION:CALL:").trim()
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$target")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    true
                }

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

