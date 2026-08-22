package com.jai.agent

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
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

    fun readRecent(context: Context, limit: Int = 20): List<String> {
        return try {
            val file = File(context.filesDir, "jai_failures.log")
            if (file.exists()) {
                file.readLines().takeLast(limit)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

class JaiAgentService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("JaiAgentService", "JAI Accessibility Engine Active")
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

            val rootNode = rootInActiveWindow ?: return

            // 1. Locate and select targeted WhatsApp contact
            if (event.packageName == "com.whatsapp" && pendingWhatsAppTargetContact != null) {
                val contactName = pendingWhatsAppTargetContact!!
                val clicked = clickNodeWithTextRecursive(rootNode, contactName)
                if (clicked) {
                    pendingWhatsAppTargetContact = null
                    pendingTextToType = pendingWhatsAppMessage
                    pendingWhatsAppMessage = null
                }
            }

            // 2. Type pending text into focused/editable input
            if (!pendingTextToType.isNullOrBlank()) {
                val typed = attemptTypeText(rootNode, pendingTextToType!!)
                if (typed) {
                    pendingTextToType = null
                    notifyResult(ActionResult.Success("Text typed."))

                    if (event.packageName == "com.whatsapp") {
                        triggerWhatsAppSendLoop(retries = 5)
                    }
                }
            }

            rootNode.recycle()
        } catch (e: Exception) {
            FailureLogger.log(this, "onAccessibilityEvent", "Event error: ${e.localizedMessage}")
        }
    }

    fun attemptTypeText(rootNode: AccessibilityNodeInfo, text: String): Boolean {
        var targetField: AccessibilityNodeInfo? = null
        return try {
            targetField = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditableNodeRecursive(rootNode)
            if (targetField != null && targetField.isEditable) {
                targetField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                var setOk = targetField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (!setOk) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("jai_text", text))
                    targetField.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    setOk = targetField.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }
                setOk
            } else {
                false
            }
        } catch (e: Exception) {
            FailureLogger.log(this, "attemptTypeText", "Typing failed: ${e.localizedMessage}")
            false
        } finally {
            targetField?.recycle()
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
            }, i * 300L)
        }
    }

    private fun attemptClickSend(rootNode: AccessibilityNodeInfo): Boolean {
        val sendBtnIds = listOf("com.whatsapp:id/send", "com.whatsapp:id/send_container", "com.whatsapp:id/entry_action_send")
        for (id in sendBtnIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                val node = nodes[0]
                var clickable: AccessibilityNodeInfo? = node
                while (clickable != null && !clickable.isClickable) {
                    clickable = clickable.parent
                }
                if (clickable != null && clickable.isClickable) {
                    val clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clickable != node) clickable.recycle()
                    nodes.forEach { it.recycle() }
                    if (clicked) {
                        notifyResult(ActionResult.Success("Message sent."))
                        return true
                    }
                }
                nodes.forEach { it.recycle() }
            }
        }

        val sendTextNodes = rootNode.findAccessibilityNodeInfosByText("Send")
        if (!sendTextNodes.isNullOrEmpty()) {
            val node = sendTextNodes[0]
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                sendTextNodes.forEach { it.recycle() }
                notifyResult(ActionResult.Success("Message sent."))
                return true
            }
            sendTextNodes.forEach { it.recycle() }
        }
        return false
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
                    val contact = payload.substringBefore(":::").trim()
                    val msg = payload.substringAfter(":::").trim()
                    targetWhatsAppContactAndSend(context, contact, msg)
                }

                trimmed.startsWith("ACTION:CHAT_SEND:") -> {
                    val payload = trimmed.removePrefix("ACTION:CHAT_SEND:").trim()
                    val contact = payload.substringBefore(":::").trim()
                    val msg = payload.substringAfter(":::").trim()
                    targetWhatsAppContactAndSend(context, contact, msg)
                }

                trimmed.startsWith("ACTION:WHATSAPP:") -> {
                    val msg = trimmed.removePrefix("ACTION:WHATSAPP:").trim()
                    sendWhatsAppGeneric(context, msg)
                }

                trimmed.startsWith("ACTION:OPEN_AND_TYPE:") -> {
                    val payload = trimmed.removePrefix("ACTION:OPEN_AND_TYPE:").trim()
                    val appName = payload.substringBefore(":::").trim()
                    val textToType = payload.substringAfter(":::").trim()
                    openAppAndType(context, appName, textToType)
                }

                trimmed.startsWith("ACTION:OPEN_APP:") -> openApp(context, trimmed.removePrefix("ACTION:OPEN_APP:").trim())
                trimmed.startsWith("ACTION:TYPE:") -> typeText(context, trimmed.removePrefix("ACTION:TYPE:").trim())
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

        private fun sendWhatsAppGeneric(context: Context, msg: String): ActionResult {
            if (msg.isBlank()) return ActionResult.Failure("No message provided.")
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
                openApp(context, "whatsapp")
            }
        }

        private fun openAppAndType(context: Context, appName: String, text: String): ActionResult {
            val launchResult = openApp(context, appName)
            if (launchResult is ActionResult.Success) {
                pendingTextToType = text
                val handler = Handler(Looper.getMainLooper())
                for (delay in listOf(600L, 1200L, 1800L, 2500L)) {
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
            val shortcutPkg = when {
                clean.contains("whatsapp") -> "com.whatsapp"
                clean.contains("youtube") -> "com.google.android.youtube"
                clean.contains("instagram") -> "com.instagram.android"
                clean.contains("chrome") -> "com.android.chrome"
                clean.contains("paytm") -> "net.one97.paytm"
                clean.contains("camera") -> "com.android.camera"
                else -> null
            }

            if (shortcutPkg != null) {
                val launchIntent = pm.getLaunchIntentForPackage(shortcutPkg)
                if (launchIntent != null) {
                    launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(launchIntent)
                    return ActionResult.Success("Opening $appNameRaw.")
                }
            }

            try {
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                val installedApps = pm.queryIntentActivities(mainIntent, 0)
                for (info in installedApps) {
                    val label = info.loadLabel(pm).toString().lowercase().replace(" ", "")
                    val pkg = info.activityInfo.packageName.lowercase()
                    if (label.contains(clean) || pkg.contains(clean)) {
                        val launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                        if (launchIntent != null) {
                            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(launchIntent)
                            return ActionResult.Success("Opening ${info.loadLabel(pm)}.")
                        }
                    }
                }
            } catch (e: Exception) {
                FailureLogger.log(context, "ACTION:OPEN_APP", "Search error: ${e.localizedMessage}")
            }

            return browse(context, appNameRaw)
        }

        private fun typeText(context: Context, text: String): ActionResult {
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
            if (query.isBlank()) return ActionResult.Failure("No contact or number given.")
            return try {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$query")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ActionResult.Success("Dialing $query.")
            } catch (e: Exception) {
                val reason = "Call intent failed: ${e.localizedMessage}"
                FailureLogger.log(context, "ACTION:CALL", reason)
                ActionResult.Failure(reason)
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
                val reason = "Alarm failed: ${e.localizedMessage}"
                FailureLogger.log(context, "ACTION:ALARM", reason)
                ActionResult.Failure(reason)
            }
        }

        private fun browse(context: Context, query: String): ActionResult {
            if (query.isBlank()) return ActionResult.Failure("No search query given.")
            return try {
                val url = if (query.startsWith("http://") || query.startsWith("https://")) {
                    query
                } else {
                    "https://www.google.com/search?q=${Uri.encode(query)}"
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ActionResult.Success("Searching for $query.")
            } catch (e: Exception) {
                val reason = "Browser failed: ${e.localizedMessage}"
                FailureLogger.log(context, "ACTION:BROWSE", reason)
                ActionResult.Failure(reason)
            }
        }
    }
}
