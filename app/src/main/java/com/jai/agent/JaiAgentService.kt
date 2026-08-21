package com.jai.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Result of an action attempt. Success/Failure carry a human-readable
 * message so the caller (FloatingOverlayService) can speak/display the
 * real outcome instead of failing silently.
 */
sealed class ActionResult {
    data class Success(val message: String) : ActionResult()
    data class Failure(val reason: String) : ActionResult()
    object NotAnAction : ActionResult()
}

/** Simple on-device failure log so you can see what's breaking most. */
object FailureLogger {
    private const val MAX_LINES = 200

    fun log(context: Context, action: String, reason: String) {
        try {
            val file = File(context.filesDir, "jai_failures.log")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val line = "$timestamp | $action | $reason"

            val existingLines = if (file.exists()) file.readLines() else emptyList()
            val trimmed = (existingLines + line).takeLast(MAX_LINES)
            file.writeText(trimmed.joinToString("\n") + "\n")

            Log.w("JAI_FAILURE", "$action -> $reason")
        } catch (e: Exception) {
            Log.e("FailureLogger", "Could not write failure log: ${e.localizedMessage}")
        }
    }

    fun readRecent(context: Context, count: Int = 20): List<String> {
        return try {
            val file = File(context.filesDir, "jai_failures.log")
            if (!file.exists()) emptyList() else file.readLines().takeLast(count)
        } catch (e: Exception) {
            emptyList()
        }
    }
}

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

        // 1. Text Injection (event-driven path; companion also retries on demand)
        if (!pendingTextToType.isNullOrBlank()) {
            attemptTypeText(rootNode, pendingTextToType!!)
        }

        // 2. Resilient WhatsApp Auto-Send with retry
        if (event.packageName == "com.whatsapp" && pendingWhatsAppMessage != null) {
            attemptWhatsAppSend(rootNode)
        }

        rootNode.recycle()
    }

    /** Tries to type into whatever is currently focused. Returns true if it succeeded. */
    fun attemptTypeText(rootNode: AccessibilityNodeInfo, text: String): Boolean {
        val focusNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val success = if (focusNode != null && focusNode.isEditable) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) pendingTextToType = null
            ok
        } else {
            false
        }
        focusNode?.recycle()
        return success
    }

    private fun attemptWhatsAppSend(rootNode: AccessibilityNodeInfo, retriesLeft: Int = 5) {
        // Fallback chain: viewId -> visible text -> content description -> give up
        var sendBtn = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")?.firstOrNull()
        if (sendBtn == null) {
            sendBtn = rootNode.findAccessibilityNodeInfosByText("Send")?.firstOrNull()
        }
        if (sendBtn == null) {
            sendBtn = findNodeByContentDescriptionAndRecycle(rootNode, "Send")
        }

        if (sendBtn != null && sendBtn.isClickable) {
            val ok = sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            sendBtn.recycle()
            if (ok) {
                pendingWhatsAppMessage = null
                notifyResult(ActionResult.Success("Message sent."))
            } else if (retriesLeft > 0) {
                scheduleWhatsAppRetry(retriesLeft - 1)
            } else {
                pendingWhatsAppMessage = null
                val reason = "Send button found but click failed"
                FailureLogger.log(this, "ACTION:WHATSAPP", reason)
                notifyResult(ActionResult.Failure(reason))
            }
        } else if (retriesLeft > 0) {
            scheduleWhatsAppRetry(retriesLeft - 1)
        } else {
            pendingWhatsAppMessage = null
            val reason = "Send button not found after retries (WhatsApp UI may have changed)"
            FailureLogger.log(this, "ACTION:WHATSAPP", reason)
            notifyResult(ActionResult.Failure(reason))
        }
    }

    private fun scheduleWhatsAppRetry(retriesLeft: Int) {
        Handler(Looper.getMainLooper()).postDelayed({
            rootInActiveWindow?.let { attemptWhatsAppSend(it, retriesLeft) }
        }, 300)
    }

    private fun findNodeByContentDescriptionAndRecycle(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString().equals(text, ignoreCase = true)) {
            return root
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child == null) continue

            val found = findNodeByContentDescriptionAndRecycle(child, text)
            if (found != null) {
                if (found != child) {
                    child.recycle()
                }
                return found
            }
            child.recycle()
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

        /** Optional hook so FloatingOverlayService can react to async results (e.g. speak them). */
        var resultListener: ((ActionResult) -> Unit)? = null

        private fun notifyResult(result: ActionResult) {
            Handler(Looper.getMainLooper()).post { resultListener?.invoke(result) }
        }

        fun executeCommand(context: Context, responseText: String): ActionResult {
            val trimmed = responseText.trim()

            return when {
                trimmed.startsWith("ACTION:OPEN_APP:") -> openApp(context, trimmed.removePrefix("ACTION:OPEN_APP:").trim())
                trimmed.startsWith("ACTION:TYPE:") -> typeText(context, trimmed.removePrefix("ACTION:TYPE:").trim())
                trimmed.startsWith("ACTION:WHATSAPP:") -> sendWhatsApp(context, trimmed.removePrefix("ACTION:WHATSAPP:").trim())
                trimmed.startsWith("ACTION:CALL:") -> placeCall(context, trimmed.removePrefix("ACTION:CALL:").trim())
                trimmed.startsWith("ACTION:ALARM:") -> setAlarm(context, trimmed.removePrefix("ACTION:ALARM:"))
                trimmed.startsWith("ACTION:BROWSE:") -> browse(context, trimmed.removePrefix("ACTION:BROWSE:").trim())
                else -> ActionResult.NotAnAction
            }
        }

        private fun openApp(context: Context, appNameRaw: String): ActionResult {
            val appName = appNameRaw.lowercase()
            if (appName.isBlank()) {
                val reason = "No app name given"
                FailureLogger.log(context, "ACTION:OPEN_APP", reason)
                return ActionResult.Failure(reason)
            }

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
                try {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(launchIntent)
                        return ActionResult.Success("Opened $appNameRaw.")
                    }
                } catch (e: Exception) {
                    FailureLogger.log(context, "ACTION:OPEN_APP", "Launch failed for $pkg: ${e.localizedMessage}")
                }
            }

            // Fallback: not a known app, search the web for it instead of failing outright.
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(appNameRaw)}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ActionResult.Success("Couldn't find $appNameRaw installed — searched the web instead.")
            } catch (e: Exception) {
                val reason = "No browser available: ${e.localizedMessage}"
                FailureLogger.log(context, "ACTION:OPEN_APP", reason)
                ActionResult.Failure(reason)
            }
        }

        private fun typeText(context: Context, text: String): ActionResult {
            if (text.isBlank()) {
                val reason = "No text given to type"
                FailureLogger.log(context, "ACTION:TYPE", reason)
                return ActionResult.Failure(reason)
            }

            pendingTextToType = text
            val svc = instance
            if (svc == null) {
                val reason = "Accessibility service not connected"
                FailureLogger.log(context, "ACTION:TYPE", reason)
                return ActionResult.Failure(reason)
            }

            // Try immediately; if it fails (field not focused yet), retry briefly via handler.
            retryTypeText(svc, text, retriesLeft = 5)
            return ActionResult.Success("Typing text.")
        }

        private fun retryTypeText(svc: JaiAgentService, text: String, retriesLeft: Int) {
            val root = svc.rootInActiveWindow
            val success = root != null && svc.attemptTypeText(root, text)
            root?.recycle()

            if (!success && retriesLeft > 0) {
                Handler(Looper.getMainLooper()).postDelayed({
                    retryTypeText(svc, text, retriesLeft - 1)
                }, 250)
            } else if (!success) {
                pendingTextToType = null
                val reason = "No editable field focused after retries"
                FailureLogger.log(svc, "ACTION:TYPE", reason)
                notifyResult(ActionResult.Failure(reason))
            }
        }

        private fun sendWhatsApp(context: Context, msg: String): ActionResult {
            if (msg.isBlank()) {
                val reason = "No message text given"
                FailureLogger.log(context, "ACTION:WHATSAPP", reason)
                return ActionResult.Failure(reason)
            }
            return try {
                pendingWhatsAppMessage = msg
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage("com.whatsapp")
                    putExtra(Intent.EXTRA_TEXT, msg)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ActionResult.Success("Opening WhatsApp to send your message.")
            } catch (e: Exception) {
                pendingWhatsAppMessage = null
                val reason = "WhatsApp not installed or couldn't open: ${e.localizedMessage}"
                FailureLogger.log(context, "ACTION:WHATSAPP", reason)
                ActionResult.Failure(reason)
            }
        }

        private fun placeCall(context: Context, target: String): ActionResult {
            if (target.isBlank()) {
                val reason = "No contact or number given"
                FailureLogger.log(context, "ACTION:CALL", reason)
                return ActionResult.Failure(reason)
            }
            return try {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$target")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ActionResult.Success("Dialing $target.")
            } catch (e: Exception) {
                val reason = "Could not open dialer: ${e.localizedMessage}"
                FailureLogger.log(context, "ACTION:CALL", reason)
                ActionResult.Failure(reason)
            }
        }

        private fun setAlarm(context: Context, paramString: String): ActionResult {
            return try {
                val parts = paramString.split(":")
                val hour = parts.getOrNull(0)?.toIntOrNull()
                val min = parts.getOrNull(1)?.toIntOrNull()
                val taskName = parts.getOrNull(2) ?: "JAI Work Schedule"

                if (hour == null || hour !in 0..23 || min == null || min !in 0..59) {
                    val reason = "Invalid time format: $paramString"
                    FailureLogger.log(context, "ACTION:ALARM", reason)
                    return ActionResult.Failure(reason)
                }

                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, min)
                    putExtra(AlarmClock.EXTRA_MESSAGE, taskName)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }

                if (intent.resolveActivity(context.packageManager) == null) {
                    val reason = "No clock app available to handle alarm intent"
                    FailureLogger.log(context, "ACTION:ALARM", reason)
                    return ActionResult.Failure(reason)
                }

                context.startActivity(intent)
                ActionResult.Success("Alarm set for %02d:%02d.".format(hour, min))
            } catch (e: Exception) {
                val reason = "Alarm failed: ${e.localizedMessage}"
                FailureLogger.log(context, "ACTION:ALARM", reason)
                ActionResult.Failure(reason)
            }
        }

        private fun browse(context: Context, query: String): ActionResult {
            if (query.isBlank()) {
                val reason = "No search query given"
                FailureLogger.log(context, "ACTION:BROWSE", reason)
                return ActionResult.Failure(reason)
            }
            return try {
                val url = if (query.startsWith("http")) query else "https://www.google.com/search?q=${Uri.encode(query)}"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ActionResult.Success("Searching for $query.")
            } catch (e: Exception) {
                val reason = "No browser available: ${e.localizedMessage}"
                FailureLogger.log(context, "ACTION:BROWSE", reason)
                ActionResult.Failure(reason)
            }
        }
    }
}
