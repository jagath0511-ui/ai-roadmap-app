private fun triggerScreenScan() {
    // 1. Inflate Full Screen Scan Canvas
    val canvasParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    )

    val scanCanvas = ScanOverlayCanvas(this) { selectedRegion ->
        // Remove canvas after selection
        windowManager?.removeView(scanCanvas)

        // 2. Capture Screen buffer & Dispatch to Gemini
        showResultCard("⚡ JAI is analyzing the selected point...")
        CoroutineScope(Dispatchers.IO).launch {
            val analysis = AiScreenAnalyzer.analyzeScreenImage(
                bitmap = currentScreenBitmap,
                userPrompt = "Explain what I selected and diagnose my confusion."
            )
            withContext(Dispatchers.Main) {
                showResultCard(analysis)
            }
        }
    }

    windowManager?.addView(scanCanvas, canvasParams)
}

