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

sealed class ActionResult {
    data class Success(val message: String) : ActionResult()
    data class Failure(val reason: String) : ActionResult()
    object NotAnAction : ActionResult()
}

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

    private var lastScanTimestamp = 0L
    private val scanThrottleMs = 150L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("JaiAgentService", "JAI Accessibility Engine Connected & Ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val pkg = event.packageName?.toString().orEmpty()
                if (pkg.isNotEmpty() && !pkg.contains("com.jai.agent")) {
                    currentForegroundApp = pkg
                }
            }

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScanTimestamp < scanThrottleMs) return
            lastScanTimestamp = currentTime

            val rootNode = rootInActiveWindow ?: return

            if (!pendingTextToType.isNullOrBlank()) {
                attemptTypeText(rootNode, pendingTextToType!!)
            }

            if (event.packageName == "com.whatsapp" && pendingWhatsAppMessage != null) {
                attemptWhatsAppSend(rootNode)
            }

            rootNode.recycle()
        } catch (e: Exception) {
            FailureLogger.log(this, "onAccessibilityEvent", "${e.localizedMessage}")
        }
    }

    fun attemptTypeText(rootNode: AccessibilityNodeInfo, text: String): Boolean {
        var focusNode: AccessibilityNodeInfo? = null
        return try {
            focusNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
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
            success
        } catch (e: Exception) {
            FailureLogger.log(this, "attemptTypeText", "${e.localizedMessage}")
            false
        } finally {
            focusNode?.recycle()
        }
    }

    private fun attemptWhatsAppSend(rootNode: AccessibilityNodeInfo, retriesLeft: Int = 5) {
        try {
            var sendBtn = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")?.firstOrNull()
            if (sendBtn == null) sendBtn = rootNode.findAccessibilityNodeInfosByText("Send")?.firstOrNull()
            if (sendBtn == null) sendBtn = findNodeByContentDescriptionAndRecycle(rootNode, "Send")

            if (sendBtn != null && sendBtn.isClickable) {
                val ok = sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                sendBtn.recycle()
                if (ok) {
                    pendingWhatsAppMessage = null
                    notifyResult(ActionResult.Success("WhatsApp message sent."))
                } else if (retriesLeft > 0) {
                    scheduleWhatsAppRetry(retriesLeft - 1)
                }
            } else if (retriesLeft > 0) {
                sendBtn?.recycle()
                scheduleWhatsAppRetry(retriesLeft - 1)
            } else {
                sendBtn?.recycle()
                pendingWhatsAppMessage = null
            }
        } catch (e: Exception) {
            FailureLogger.log(this, "attemptWhatsAppSend", "${e.localizedMessage}")
        }
    }

    private fun scheduleWhatsAppRetry(retriesLeft: Int) {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                rootInActiveWindow?.let { root ->
                    attemptWhatsAppSend(root, retriesLeft)
                    root.recycle()
                }
            } catch (e: Exception) {}
        }, 300)
    }

    private fun findNodeByContentDescriptionAndRecycle(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString().equals(text, ignoreCase = true)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByContentDescriptionAndRecycle(child, text)
            if (found != null) {
                if (found != child) child.recycle()
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

        /**
         * Dynamic App Launcher: Scans ALL installed packages on the phone,
         * normalizes spacing ("whats app" -> "whatsapp", "gemini app" -> "gemini"),
         * and opens the real installed app!
         */
        fun openApp(context: Context, appNameRaw: String): ActionResult {
            val cleanQuery = appNameRaw.lowercase()
                .replace("app", "")
                .replace("application", "")
                .replace(" ", "")
                .replace("'", "")
                .trim()

            if (cleanQuery.isBlank()) {
                return ActionResult.Failure("No app name specified.")
            }

            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val installedApps = pm.queryIntentActivities(mainIntent, 0)

            for (resolveInfo in installedApps) {
                val appLabel = resolveInfo.loadLabel(pm).toString().lowercase().replace(" ", "").replace("'", "")
                val pkgName = resolveInfo.activityInfo.packageName.lowercase()

                if (appLabel == cleanQuery || appLabel.contains(cleanQuery) || cleanQuery.contains(appLabel) || pkgName.contains(cleanQuery)) {
                    val launchIntent = pm.getLaunchIntentForPackage(resolveInfo.activityInfo.packageName)
                    if (launchIntent != null) {
                        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(launchIntent)
                        return ActionResult.Success("Opening ${resolveInfo.loadLabel(pm)}.")
                    }
                }
            }

            return BrowserAgent.routeWebIntent(context, appNameRaw)
        }

        private fun typeText(context: Context, text: String): ActionResult {
            if (text.isBlank()) return ActionResult.Failure("No text given")
            pendingTextToType = text
            val svc = instance ?: return ActionResult.Failure("Accessibility service off")
            return ActionResult.Success("Typing text.")
        }

        private fun sendWhatsApp(context: Context, msg: String): ActionResult {
            if (msg.isBlank()) return ActionResult.Failure("No message text given")
            return try {
                pendingWhatsAppMessage = msg
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage("com.whatsapp")
                    putExtra(Intent.EXTRA_TEXT, msg)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ActionResult.Success("Opening WhatsApp to send message.")
            } catch (e: Exception) {
                pendingWhatsAppMessage = null
                ActionResult.Failure("WhatsApp not installed.")
            }
        }

        private fun placeCall(context: Context, target: String): ActionResult {
            return try {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$target")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ActionResult.Success("Dialing $target.")
            } catch (e: Exception) {
                ActionResult.Failure("Could not open dialer.")
            }
        }

        private fun setAlarm(context: Context, paramString: String): ActionResult {
            return try {
                val parts = paramString.split(":")
                val hour = parts.getOrNull(0)?.toIntOrNull() ?: 7
                val min = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val taskName = parts.getOrNull(2) ?: "JAI Alarm"

                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, min)
                    putExtra(AlarmClock.EXTRA_MESSAGE, taskName)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ActionResult.Success("Alarm set for %02d:%02d.".format(hour, min))
            } catch (e: Exception) {
                ActionResult.Failure("Alarm failed: ${e.localizedMessage}")
            }
        }

        private fun browse(context: Context, query: String): ActionResult {
            return BrowserAgent.routeWebIntent(context, query)
        }
    }
}

