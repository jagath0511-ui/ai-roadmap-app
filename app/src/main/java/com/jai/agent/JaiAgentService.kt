package com.jai.agent

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
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
            Log.e("FailureLogger", "Log error: ${e.localizedMessage}")
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
    private val scanThrottleMs = 120L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("JaiAgentService", "JAI Connected & Ready")
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

            // 1. Process Pending Text Injection
            if (!pendingTextToType.isNullOrBlank()) {
                val success = attemptTypeText(rootNode, pendingTextToType!!)
                if (success) {
                    pendingTextToType = null
                    notifyResult(ActionResult.Success("Text entered."))
                }
            }

            // 2. Process Pending WhatsApp Dispatch
            if (event.packageName == "com.whatsapp" && pendingWhatsAppMessage != null) {
                attemptWhatsAppSend(rootNode)
            }

            rootNode.recycle()
        } catch (e: Exception) {
            FailureLogger.log(this, "onAccessibilityEvent", "${e.localizedMessage}")
        }
    }

    /**
     * Robust input search: Scans for editable nodes, focuses them, and injects text.
     */
    fun attemptTypeText(rootNode: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val targetField = findEditableNodeRecursive(rootNode)
            if (targetField != null) {
                targetField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val setOk = targetField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                targetField.recycle()
                setOk
            } else {
                false
            }
        } catch (e: Exception) {
            FailureLogger.log(this, "attemptTypeText", "${e.localizedMessage}")
            false
        }
    }

    private fun findEditableNodeRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNodeRecursive(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun attemptWhatsAppSend(rootNode: AccessibilityNodeInfo, retriesLeft: Int = 5) {
        try {
            var sendBtn = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")?.firstOrNull()
            if (sendBtn == null) sendBtn = rootNode.findAccessibilityNodeInfosByText("Send")?.firstOrNull()
            if (sendBtn == null) sendBtn = findNodeByContentDesc(rootNode, "Send")

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
        }, 350)
    }

    private fun findNodeByContentDesc(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString().equals(text, ignoreCase = true)) return AccessibilityNodeInfo.obtain(root)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByContentDesc(child, text)
            child.recycle()
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
        var currentForegroundApp: String = "Home"
        var pendingTextToType: String? = null
        var pendingWhatsAppMessage: String? = null
        var resultListener: ((ActionResult) -> Unit)? = null

        private fun notifyResult(result: ActionResult) {
            Handler(Looper.getMainLooper()).post { resultListener?.invoke(result) }
        }

        fun executeCommand(context: Context, responseText: String): ActionResult {
            val trimmed = responseText.trim()
            return when {
                trimmed.startsWith("ACTION:OPEN_AND_TYPE:") -> {
                    val payload = trimmed.removePrefix("ACTION:OPEN_AND_TYPE:").trim()
                    val parts = payload.split(":::")
                    val appName = parts.getOrNull(0) ?: ""
                    val textToType = parts.getOrNull(1) ?: ""
                    openAppAndType(context, appName, textToType)
                }
                trimmed.startsWith("ACTION:OPEN_APP:") -> openApp(context, trimmed.removePrefix("ACTION:OPEN_APP:").trim())
                trimmed.startsWith("ACTION:TYPE:") -> typeText(trimmed.removePrefix("ACTION:TYPE:").trim())
                trimmed.startsWith("ACTION:WHATSAPP:") -> sendWhatsApp(context, trimmed.removePrefix("ACTION:WHATSAPP:").trim())
                trimmed.startsWith("ACTION:CALL:") -> placeCall(context, trimmed.removePrefix("ACTION:CALL:").trim())
                trimmed.startsWith("ACTION:GMAIL:") -> sendEmail(context, trimmed.removePrefix("ACTION:GMAIL:").trim())
                trimmed.startsWith("ACTION:SMS:") -> sendSms(context, trimmed.removePrefix("ACTION:SMS:").trim())
                trimmed.startsWith("ACTION:ALARM:") -> setAlarm(context, trimmed.removePrefix("ACTION:ALARM:"))
                trimmed.startsWith("ACTION:BROWSE:") -> browse(context, trimmed.removePrefix("ACTION:BROWSE:").trim())
                else -> ActionResult.NotAnAction
            }
        }

        private fun openAppAndType(context: Context, appName: String, text: String): ActionResult {
            val launchResult = openApp(context, appName)
            if (launchResult is ActionResult.Success) {
                pendingTextToType = text
                // Schedule repeated injection attempts while target app loads
                val handler = Handler(Looper.getMainLooper())
                for (delay in listOf(600L, 1200L, 1800L)) {
                    handler.postDelayed({
                        instance?.rootInActiveWindow?.let { root ->
                            if (pendingTextToType != null) {
                                val ok = instance?.attemptTypeText(root, pendingTextToType!!) ?: false
                                if (ok) pendingTextToType = null
                            }
                            root.recycle()
                        }
                    }, delay)
                }
                return ActionResult.Success("Opening $appName and typing.")
            }
            return launchResult
        }

        fun openApp(context: Context, appNameRaw: String): ActionResult {
            val clean = appNameRaw.lowercase().replace("app", "").replace("application", "").replace(" ", "").trim()
            if (clean.isBlank()) return ActionResult.Failure("No app specified.")

            val pm = context.packageManager
            val directPackages = when {
                clean.contains("youtube") -> listOf("com.google.android.youtube")
                clean.contains("whatsapp") -> listOf("com.whatsapp", "com.whatsapp.w4b")
                clean.contains("gemini") -> listOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox")
                clean.contains("instagram") -> listOf("com.instagram.android")
                clean.contains("gmail") -> listOf("com.google.android.gm")
                clean.contains("paytm") -> listOf("net.one97.paytm")
                clean.contains("chrome") -> listOf("com.android.chrome")
                else -> emptyList()
            }

            for (pkg in directPackages) {
                try {
                    val launchIntent = pm.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(launchIntent)
                        return ActionResult.Success("Opening $appNameRaw.")
                    }
                } catch (e: Exception) {}
            }

            try {
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                val installedApps = pm.queryIntentActivities(mainIntent, 0)

                for (info in installedApps) {
                    val label = info.loadLabel(pm).toString().lowercase().replace(" ", "")
                    val pkg = info.activityInfo.packageName.lowercase()
                    if (label == clean || label.contains(clean) || pkg.contains(clean)) {
                        val launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                        if (launchIntent != null) {
                            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(launchIntent)
                            return ActionResult.Success("Opening ${info.loadLabel(pm)}.")
                        }
                    }
                }
            } catch (e: Exception) {}

            return BrowserAgent.routeWebIntent(context, appNameRaw)
        }

        private fun typeText(text: String): ActionResult {
            if (text.isBlank()) return ActionResult.Failure("No text given.")
            pendingTextToType = text
            val root = instance?.rootInActiveWindow
            if (root != null) {
                val ok = instance?.attemptTypeText(root, text) ?: false
                root.recycle()
                if (ok) {
                    pendingTextToType = null
                    return ActionResult.Success("Text typed.")
                }
            }
            return ActionResult.Success("Queued text to type.")
        }

        private fun placeCall(context: Context, query: String): ActionResult {
            val resolvedNumber = ContactResolver.resolvePhoneNumber(context, query)
            if (resolvedNumber.isNullOrBlank()) {
                return ActionResult.Failure("Could not find contact '$query'.")
            }

            val hasCallPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
            return try {
                val action = if (hasCallPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL
                val intent = Intent(action, Uri.parse("tel:$resolvedNumber")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ActionResult.Success("Calling $query ($resolvedNumber).")
            } catch (e: Exception) {
                ActionResult.Failure("Call failed: ${e.localizedMessage}")
            }
        }

        private fun sendWhatsApp(context: Context, msg: String): ActionResult {
            return try {
                pendingWhatsAppMessage = msg
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage("com.whatsapp")
                    putExtra(Intent.EXTRA_TEXT, msg)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ActionResult.Success("Opening WhatsApp.")
            } catch (e: Exception) {
                pendingWhatsAppMessage = null
                ActionResult.Failure("WhatsApp not available.")
            }
        }

        private fun sendEmail(context: Context, body: String): ActionResult {
            return try {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:")
                    putExtra(Intent.EXTRA_TEXT, body)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ActionResult.Success("Opening Email app.")
            } catch (e: Exception) {
                ActionResult.Failure("No email app found.")
            }
        }

        private fun sendSms(context: Context, body: String): ActionResult {
            return try {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:")
                    putExtra("sms_body", body)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ActionResult.Success("Opening Messages app.")
            } catch (e: Exception) {
                ActionResult.Failure("No messaging app found.")
            }
        }

        private fun setAlarm(context: Context, paramString: String): ActionResult {
            return try {
                val parts = paramString.split(":")
                val hour = parts.getOrNull(0)?.toIntOrNull() ?: 7
                val min = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val label = parts.getOrNull(2) ?: "JAI Alarm"

                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, min)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ActionResult.Success("Alarm set for %02d:%02d.".format(hour, min))
            } catch (e: Exception) {
                ActionResult.Failure("Alarm failed.")
            }
        }

        private fun browse(context: Context, query: String): ActionResult {
            return BrowserAgent.routeWebIntent(context, query)
        }
    }
}
