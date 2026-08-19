package com.jai.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingBubble: View? = null
    private var controlCard: View? = null
    private var voiceEngine: VoiceEngine? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification()

        val resultCode = intent?.getIntExtra("RESULT_CODE", -1) ?: -1
        val dataIntent = intent?.getParcelableExtra<Intent>("DATA_INTENT")

        if (resultCode != -1 && dataIntent != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, dataIntent)
            setupScreenCaptureReader()
        }

        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channelId = "jai_overlay_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "JAI Active", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("⚡ JAI Companion")
            .setContentText("Screen Mentor & Agents Active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(101, notification)
    }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        voiceEngine = VoiceEngine(this)
        setupFloatingMascot()
    }

    private fun setupScreenCaptureReader() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "JAIScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun captureCurrentScreen(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()

        return Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
    }

    private fun setupFloatingMascot() {
        val density = resources.displayMetrics.density
        val sizePx = (65 * density).toInt()

        val mascotView = ImageView(this).apply {
            val resId = resources.getIdentifier("jai_mascot", "drawable", packageName)
            if (resId != 0) {
                setImageResource(resId)
            } else {
                setImageResource(android.R.drawable.sym_def_app_icon)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER

            val glowRing = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke((2 * density).toInt(), Color.parseColor("#00E5FF"))
            }
            background = glowRing
        }
        floatingBubble = mascotView

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 350
        }

        mascotView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager?.updateViewLayout(floatingBubble, params)
                        } catch (ignored: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = Math.abs(event.rawX - initialTouchX)
                        val diffY = Math.abs(event.rawY - initialTouchY)
                        if (diffX < 15 && diffY < 15) {
                            toggleControlCard()
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager?.addView(floatingBubble, params)
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun toggleControlCard() {
        if (controlCard != null) {
            try {
                windowManager?.removeView(controlCard)
            } catch (ignored: Exception) {}
            controlCard = null
            return
        }

        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val shape = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = 32f
                setStroke(2, Color.parseColor("#00E5FF"))
            }
            background = shape
            setPadding(40, 40, 40, 40)
        }

        val title = TextView(this).apply {
            text = "⚡ JAI Agent Hub"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 17f
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, 20)
        }

        val inputField = EditText(this).apply {
            hint = "Ask or specify focus area..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 14f
            val editBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 16f
            }
            background = editBg
            setPadding(30, 24, 30, 24)
        }

        val outputText = TextView(this).apply {
            text = "Ready. Tap an agent below."
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
            setPadding(0, 20, 0, 20)
        }

        val scrollArea = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 380)
            addView(outputText)
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 15, 0, 10)
        }

        val btnScan = createStyledButton("🔍 Scan") {
            outputText.text = "⚡ Capturing screen & analyzing..."
            val screenshot = captureCurrentScreen()

            CoroutineScope(Dispatchers.IO).launch {
                val prompt = inputField.text.toString().ifEmpty {
                    "Diagnose what is on this screen, explain difficult concepts step-by-step, and provide guidance."
                }
                val result = AiScreenAnalyzer.analyzeScreenImage(bitmap = screenshot, customPrompt = prompt)
                withContext(Dispatchers.Main) {
                    outputText.text = result
                    voiceEngine?.speak("Screen analyzed.")
                }
            }
        }

        val btnBrowse = createStyledButton("🌐 Browse") {
            val query = inputField.text.toString().trim()
            if (query.isEmpty()) {
                Toast.makeText(this, "Enter search topic", Toast.LENGTH_SHORT).show()
                return@createStyledButton
            }
            outputText.text = "🌐 Searching: '$query'..."
            CoroutineScope(Dispatchers.IO).launch {
                val result = BrowserAgent.browseAndExtract(query)
                withContext(Dispatchers.Main) {
                    outputText.text = result
                }
            }
        }

        val btnTrack = createStyledButton("💰 Track") {
            val query = inputField.text.toString().trim()
            if (query.isEmpty()) {
                Toast.makeText(this, "Enter product name", Toast.LENGTH_SHORT).show()
                return@createStyledButton
            }
            PriceTrackerWorker.schedule24HourTracker(this, query, 0.0)
            outputText.text = "✅ 24h Tracker set for '$query'."
        }

        val btnVoice = createStyledButton("🎙️ Voice") {
            val status = voiceEngine?.checkModelsStatus() ?: "Voice engine unavailable"
            outputText.text = "$status\n\n⚡ JAI offline voice active."
            voiceEngine?.speak("JAI voice engine is ready.")
        }

        buttonRow.addView(btnScan)
        buttonRow.addView(btnBrowse)
        buttonRow.addView(btnTrack)
        buttonRow.addView(btnVoice)

        val closeBtn = TextView(this).apply {
            text = "✕ Close"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 15, 0, 0)
            setOnClickListener {
                windowManager?.removeView(controlCard)
                controlCard = null
            }
        }

        cardLayout.addView(title)
        cardLayout.addView(inputField)
        cardLayout.addView(buttonRow)
        cardLayout.addView(scrollArea)
        cardLayout.addView(closeBtn)

        val cardParams = WindowManager.LayoutParams(
            880,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        controlCard = cardLayout
        try {
            windowManager?.addView(controlCard, cardParams)
        } catch (ignored: Exception) {}
    }

    private fun createStyledButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.parseColor("#0F172A"))
            textSize = 11f
            paint.isFakeBoldText = true
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#00E5FF"))
                cornerRadius = 14f
            }
            background = btnBg
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(6, 0, 6, 0)
            }
            setOnClickListener { onClick() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        voiceEngine?.shutdown()
        if (floatingBubble != null) windowManager?.removeView(floatingBubble)
        if (controlCard != null) windowManager?.removeView(controlCard)
    }
}
