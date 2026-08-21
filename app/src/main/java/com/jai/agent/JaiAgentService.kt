package com.jai.agent

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
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

sealed class ActionResult {
    data class Success(val message: String) : ActionResult()
    data class Failure(val reason: String) : ActionResult()
    object NotAnAction : ActionResult()
}

class JaiAgentService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("JaiAgentService", "Accessibility Engine Active")
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

            // 1. If searching for a contact in WhatsApp
            if (event.packageName == "com.whatsapp" && pendingWhatsAppTargetContact != null) {
                val contactName = pendingWhatsAppTargetContact!!
                val clicked = clickNodeWithTextRecursive(rootNode, contactName)
                if (clicked) {
                    pendingWhatsAppTargetContact = null
                    pendingTextToType = pendingWhatsAppMessage
                    pendingWhatsAppMessage = null
                }
            }

            // 2. Type pending text into active field
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
            Log.e("JaiAgentService", "Event error: ${e.localizedMessage}")
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
                var setOk = targetField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (!setOk) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("jai_text", text))
                    targetField.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    setOk = targetField.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }
                targetField.recycle()
                setOk
            } else {
                false
            }
        } catch (e: Exception) {
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
            }, i * 300L)
        }
    }

    private fun attemptClickSend(rootNode: AccessibilityNodeInfo): Boolean {
        val sendBtnIds = listOf("com.whatsapp:id/send", "com.whatsapp:id/send_container")
        for (id in sendBtnIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                var clickable: AccessibilityNodeInfo? = node
                while (clickable != null && !clickable.isClickable) {
                    clickable = clickable.parent
                }
                if (clickable != null && clickable.isClickable) {
                    val clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clickable != node) clickable.recycle()
                    node.recycle()
                    if (clicked) {
                        notifyResult(ActionResult.Success("Message sent."))
                        return true
                    }
                }
                node.recycle()
            }
        }

        val sendTextNodes = rootNode.findAccessibilityNodeInfosByText("Send")
        for (node in sendTextNodes) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                node.recycle()
                notifyResult(ActionResult.Success("Message sent."))
                return true
            }
            node.recycle()
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
                // 1. WhatsApp with specific contact
                trimmed.startsWith("ACTION:WHATSAPP_TARGET:") -> {
                    val payload = trimmed.removePrefix("ACTION:WHATSAPP_TARGET:").trim()
                    val contact = payload.substringBefore(":::").trim()
                    val msg = payload.substringAfter(":::").trim()
                    targetWhatsAppContactAndSend(context, contact, msg)
                }

                // 2. Chat Send Alias
                trimmed.startsWith("ACTION:CHAT_SEND:") -> {
                    val payload = trimmed.removePrefix("ACTION:CHAT_SEND:").trim()
                    val contact = payload.substringBefore(":::").trim()
                    val msg = payload.substringAfter(":::").trim()
                    targetWhatsAppContactAndSend(context, contact, msg)
                }

                // 3. Generic WhatsApp Send
                trimmed.startsWith("ACTION:WHATSAPP:") -> {
                    val msg = trimmed.removePrefix("ACTION:WHATSAPP:").trim()
                    sendWhatsAppGeneric(context, msg)
                }

                // 4. Open App and Type
                trimmed.startsWith("ACTION:OPEN_AND_TYPE:") -> {
                    val payload = trimmed.removePrefix("ACTION:OPEN_AND_TYPE:").trim()
                    val appName = payload.substringBefore(":::").trim()
                    val textToType = payload.substringAfter(":::").trim()
                    openAppAndType(context, appName, textToType)
                }

                // 5. Standard System Controls
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

        private fun sendWhatsAppGeneric(context: Context, msg: String): ActionResult {
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
                ActionResult.Success("Calling $query.")
            } catch (e: Exception) {
                ActionResult.Failure("Call failed.")
            }
        }

        private fun setAlarm(context: Context, paramString: String): ActionResult {
            return try {
                val parts = paramString.split(":")
                val hour = parts.getOrNull(0)?.toIntOrNull() ?: 7
                val min = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val label = parts.getOrNull(2) ?: "Alarm"

                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, min)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ActionResult.Success("Alarm set.")
            } catch (e: Exception) {
                ActionResult.Failure("Alarm failed.")
            }
        }

        private fun browse(context: Context, query: String): ActionResult {
            return BrowserAgent.routeWebIntent(context, query)
        }
    }
}

