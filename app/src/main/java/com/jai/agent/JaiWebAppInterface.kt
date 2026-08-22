package com.jai.agent

import android.content.Context
import android.webkit.JavascriptInterface

class JaiWebAppInterface(
    private val context: Context,
    private val currentAppId: String,
    private val onActionRequested: (String) -> Unit,
    private val onCloseRequested: () -> Unit
) {
    private val memoryDb = CognitiveMemoryDb(context)

    @JavascriptInterface
    fun saveState(stateJson: String) {
        memoryDb.saveAppState(currentAppId, stateJson)
    }

    @JavascriptInterface
    fun loadState(): String {
        return memoryDb.getAppState(currentAppId) ?: "{}"
    }

    @JavascriptInterface
    fun executeAction(command: String) {
        onActionRequested(command)
    }

    @JavascriptInterface
    fun closeApp() {
        onCloseRequested()
    }
}
