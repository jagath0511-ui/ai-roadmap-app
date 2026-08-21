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
    private val scanThrottleMs = 100L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("JaiAgentService", "JAI Accessibility Connected & Ready")
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

            // 1. WhatsApp Targeted Contact Navigation
            if (event.packageName == "com.whatsapp" && pendingWhatsAppTargetContact != null) {
                val contactName = pendingWhatsAppTargetContact!!
                val found = clickNodeWithTextRecursive(rootNode, contactName)
                if (found) {
                    pendingWhatsAppTargetContact = null
                    pendingTextToType = pendingWhatsAppMessage
                    pendingWhatsAppMessage = null
                }
            }

            // 2. Text Injection & Auto-Send Dispatcher
            if (!pendingTextToType.isNullOrBlank()) {
                val success = attemptTypeText(rootNode, pendingTextToType!!)
                if (success) {
                    pendingTextToType = null
                    notifyResult(ActionResult.Success("Text entered."))

                    if (event.packageName == "com.whatsapp") {
                        triggerWhatsAppSendLoop(retries = 6)
                    }
                }
            }

            rootNode.recycle()
        } catch (e: Exception) {
            FailureLogger.log(this, "onAccessibilityEvent", "${e.localizedMessage}")
        }
    }

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

    private fun clickNodeWithTextRecursive(node: AccessibilityNodeInfo, text: String): Boolean {
        val nodeText = node.text?.toString().orEmpty()
        val nodeDesc = node.contentDescription?.toString().orEmpty()

        if (nodeText.contains(text, ignoreCase = true) || nodeDesc.contains(text, ignoreCase = true)) {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) {
                target = target.parent
            }
            if (target != null && target.isClickable) {
                val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (target != node) target.recycle()
                return ok
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val clicked = clickNodeWithTextRecursive(child, text)
            child.recycle()
            if (clicked) return true
        }
        return false
    }

    private fun triggerWhatsAppSendLoop(retries: Int) {
        val handler = Handler(Looper.getMainLooper())
        for (i in 1..retries) {
            handler.postDelayed({
                rootInActiveWindow?.let { root ->
                    val sent = attemptClickSend(root)
                    root.recycle()
                    if (sent) return@postDelayed
                }
            }, i * 250L)
        }
    }

    private fun attemptClickSend(rootNode: AccessibilityNodeInfo): Boolean {
        var sendBtn = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")?.firstOrNull()
        if (sendBtn == null) sendBtn = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send_container")?.firstOrNull()
        if (sendBtn == null) sendBtn = findNodeByContentDesc(rootNode, "Send")
        if (sendBtn == null) sendBtn = rootNode.findAccessibilityNodeInfosByText("Send")?.firstOrNull()

        if (sendBtn != null) {
            var clickableTarget: AccessibilityNodeInfo? = sendBtn
            while (clickableTarget != null && !clickableTarget.isClickable) {
                clickableTarget = clickableTarget.parent
            }

            if (clickableTarget != null && clickableTarget.isClickable) {
                val ok = clickableTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clickableTarget != sendBtn) clickableTarget.recycle()
                sendBtn.recycle()
                if (ok) {
                    notifyResult(ActionResult.Success("Message sent."))
                    return true
                }
            }
            sendBtn.recycle()
        }
        return false
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
        var pendingWhatsAppTargetContact: String? = null
        var resultListener: ((ActionResult) -> Unit)? = null

        private fun notifyResult(result: ActionResult) {
            Handler(Looper.getMainLooper()).post { resultListener?.invoke(result) }
        }

        fun executeCommand(context: Context, responseText: String): ActionResult {
            val trimmed = responseText.trim()
            return when {
                trimmed.startsWith("ACTION:WHATSAPP_TARGET:") -> {
                    val payload = trimmed.removePrefix("ACTION:WHATSAPP_TARGET:").trim()
                    val contact = payload.substringBefore(":::")
                    val msg = payload.substringAfter(":::")
                    targetWhatsAppContactAndSend(context, contact, msg)
                }
                trimmed.startsWith("ACTION:OPEN_AND_TYPE:") -> {
                    val payload = trimmed.removePrefix("ACTION:OPEN_AND_TYPE:").trim()
                    val appName = payload.substringBefore(":::")
                    val textToType = payload.substringAfter(":::")
                    openAppAndType(context, appName, textToType)
                }
                trimmed.startsWith("ACTION:OPEN_APP:") -> openApp(context, trimmed.removePrefix("ACTION:OPEN_APP:").trim())
                trimmed.startsWith("ACTION:TYPE:") -> typeText(trimmed.removePrefix("ACTION:TYPE:").trim())
                trimmed.startsWith("ACTION:CALL:") -> placeCall(context, trimmed.removePrefix("ACTION:CALL:").trim())
                trimmed.startsWith("ACTION:ALARM:") -> setAlarm(context, trimmed.removePrefix("ACTION:ALARM:"))
                trimmed.startsWith("ACTION:BROWSE:") -> browse(context, trimmed.removePrefix("ACTION:BROWSE:").trim())
                else -> ActionResult.NotAnAction
            }
        }

        private fun targetWhatsAppContactAndSend(context: Context, contact: String, msg: String): ActionResult {
            pendingWhatsAppTargetContact = contact
            pendingWhatsAppMessage = msg
            return openApp(context, "whatsapp")
        }

        private fun openAppAndType(context: Context, appName: String, text: String): ActionResult {
            val launchResult = openApp(context, appName)
            if (launchResult is ActionResult.Success) {
                pendingTextToType = text
                val handler = Handler(Looper.getMainLooper())
                for (delay in listOf(500L, 1000L, 1600L, 2300L)) {
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
            val clean = appNameRaw.lowercase().replace("app", "").replace("application", "").replace(" ", "").replace("'", "").trim()
            if (clean.isBlank()) return ActionResult.Failure("No app specified.")

            val pm = context.packageManager
            val directPackages = when {
                clean.contains("youtube") -> listOf("com.google.android.youtube")
                clean.contains("whatsapp") -> listOf("com.whatsapp", "com.whatsapp.w4b")
                clean.contains("gemini") -> listOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox")
                clean.contains("instagram") -> listOf("com.instagram.android")
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
                    val label = info.loadLabel(pm).toString().lowercase().replace(" ", "").replace("'", "")
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
                return ActionResult.Failure("Contact '$query' not found.")
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
