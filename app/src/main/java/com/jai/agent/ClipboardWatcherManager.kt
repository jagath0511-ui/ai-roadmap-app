package com.jai.agent

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.util.Patterns

enum class ClipType {
    PHONE_NUMBER,
    URL_LINK,
    GENERAL_TEXT
}

data class ClipItem(
    val text: String,
    val type: ClipType
)

class ClipboardWatcherManager(
    private val context: Context,
    private val onNewClipDetected: (ClipItem) -> Unit
) {
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private var lastCopiedText: String = ""

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        checkClipboard()
    }

    fun startListening() {
        try {
            clipboardManager?.addPrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
            FailureLogger.log(context, "ClipboardWatcherManager", "Failed to start listener: ${e.localizedMessage}")
        }
    }

    fun stopListening() {
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
            FailureLogger.log(context, "ClipboardWatcherManager", "Failed to stop listener: ${e.localizedMessage}")
        }
    }

    private fun checkClipboard() {
        try {
            val clip = clipboardManager?.primaryClip ?: return
            if (clip.itemCount > 0 && (clipboardManager.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true ||
                clipboardManager.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) == true)) {
                
                val text = clip.getItemAt(0).text?.toString()?.trim().orEmpty()
                if (text.isNotBlank() && text != lastCopiedText) {
                    lastCopiedText = text
                    val clipType = categorizeText(text)
                    onNewClipDetected(ClipItem(text, clipType))
                }
            }
        } catch (e: Exception) {
            FailureLogger.log(context, "ClipboardWatcherManager", "Clipboard check exception: ${e.localizedMessage}")
        }
    }

    private fun categorizeText(text: String): ClipType {
        return when {
            Patterns.WEB_URL.matcher(text).matches() -> ClipType.URL_LINK
            Patterns.PHONE.matcher(text).matches() && text.length >= 7 -> ClipType.PHONE_NUMBER
            else -> ClipType.GENERAL_TEXT
        }
    }
}
