package com.jai.agent

import android.annotation.SuppressLint
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
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class FloatingOverlayService : Service(), TextToSpeech.OnInitListener {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayContainer: LinearLayout
    private lateinit var fullDeckView: LinearLayout
    private lateinit var miniBubbleView: TextView
    private lateinit var inputEditText: EditText
    private lateinit var outputTextView: TextView

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var speechRecognizer: SpeechRecognizer? = null

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var isDeckExpanded = true

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 1. Initialize Foreground Notification Channel
        startForegroundServiceNotification()

        // 2. Initialize Native Text-To-Speech Engine
        tts = TextToSpeech(this, this)

        // 3. Initialize Speech Recognizer
        initSpeechRecognizer()

        // 4. Build and Display Floating Deck UI
        createFloatingOverlayDeck()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
                Log.d("FloatingOverlayService", "TTS Engine Ready.")
            }
        }
    }

    // ==========================================
    // 1. FOREGROUND SERVICE NOTIFICATION
    // ==========================================
    private fun startForegroundServiceNotification() {
        val channelId = "jai_agent_overlay_channel"
        val channelName = "JAI Operating System Agent"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "JAI Hands-Free Assistant is running in background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("⚡ JAI Agent Active")
            .setContentText("Hands-Free OS Controller Ready")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    // ==========================================
    // 2. PROGRAMMATIC FLOATING DECK HUD CREATION
    // ==========================================
    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingOverlayDeck() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 120
        }

        overlayContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // --- MINI BUBBLE (Dormant State) ---
        miniBubbleView = TextView(this).apply {
            text = "⚡ JAI"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0B132B"))
                cornerRadius = 30f
                setStroke(3, Color.parseColor("#00E5FF"))
            }
            visibility = View.GONE
            setOnClickListener {
                expandDeck(params)
            }
        }

        // --- FULL EXPANDED DECK ---
        fullDeckView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#070A13"))
                cornerRadius = 28f
                setStroke(3, Color.parseColor("#00E5FF"))
            }
            elevation = 16f
        }

        // Header
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val titleText = TextView(this).apply {
            text = "⚡ JAI Omniscient Deck"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val hideButton = Button(this).apply {
            text = "🙈 Hide"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C2541"))
                cornerRadius = 14f
            }
            setOnClickListener {
                collapseDeck(params)
            }
        }

        headerLayout.addView(titleText)
        headerLayout.addView(hideButton)

        // Text Input Field
        inputEditText = EditText(this).apply {
            hint = "Type prompt or tap Voice..."
            setHintTextColor(Color.parseColor("#718096"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(20, 18, 20, 18)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#111726"))
                cornerRadius = 14f
                setStroke(2, Color.parseColor("#1E293B"))
            }
        }

        // Action Buttons Grid (Screen, Back, Front, Brain, Voice, Browse)
        val gridLayout = GridLayout(this).apply {
            rowCount = 2
            columnCount = 3
            setPadding(0, 16, 0, 16)
        }

        val btnScreen = createDeckButton("🔍 Screen", "#00E5FF") {
            processAction("Analyze current screen and describe what app is opened.", null)
        }
        val btnBack = createDeckButton("📷 Back", "#00E5FF") {
            processAction("Capture environment via rear camera.", null)
        }
        val btnFront = createDeckButton("📸 Front", "#00E5FF") {
            processAction("Capture user via front camera.", null)
        }
        val btnBrain = createDeckButton("🧠 Brain", "#00E5FF") {
            val text = inputEditText.text.toString().trim()
            if (text.isNotEmpty()) {
                processAction(text, null)
                inputEditText.text.clear()
            } else {
                Toast.makeText(applicationContext, "Enter a prompt first", Toast.LENGTH_SHORT).show()
            }
        }
        val btnVoice = createDeckButton("🎙️ Voice", "#00E5FF") {
            startListening()
        }
        val btnBrowse = createDeckButton("🌐 Browse", "#00E5FF") {
            val text = inputEditText.text.toString().trim()
            val query = if (text.isNotEmpty()) text else "latest news"
            JaiAgentService.executeCommand(applicationContext, "ACTION:BROWSE:$query")
        }

        gridLayout.addView(btnScreen)
        gridLayout.addView(btnBack)
        gridLayout.addView(btnFront)
        gridLayout.addView(btnBrain)
        gridLayout.addView(btnVoice)
        gridLayout.addView(btnBrowse)

        // Output Result Display Text
        outputTextView = TextView(this).apply {
            text = "JAI System Ready. Ask me to call, set alarms, browse, or send messages."
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(16, 16, 16, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10172A"))
                cornerRadius = 14f
            }
        }

        // Close Deck Button
        val closeDeckButton = Button(this).apply {
            text = "✕ Close Deck"
            setTextColor(Color.parseColor("#A0AEC0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = null
            setOnClickListener {
                collapseDeck(params)
            }
        }

        // Assemble Full View
        fullDeckView.addView(headerLayout)
        fullDeckView.addView(inputEditText)
        fullDeckView.addView(gridLayout)
        fullDeckView.addView(outputTextView)
        fullDeckView.addView(closeDeckButton)

        overlayContainer.addView(fullDeckView)
        overlayContainer.addView(miniBubbleView)

        // Enable Dragging on Overlay
        enableTouchDrag(overlayContainer, params)

        windowManager.addView(overlayContainer, params)
    }

    private fun createDeckButton(title: String, accentHex: String, onClick: () -> Unit): Button {
        val displayMetrics = resources.displayMetrics
        val widthInPx = (displayMetrics.widthPixels * 0.26).toInt()

        return Button(this).apply {
            text = title
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(accentHex))
                cornerRadius = 14f
            }
            layoutParams = GridLayout.LayoutParams().apply {
                width = widthInPx
                height = LinearLayout.LayoutParams.WRAP_CONTENT
                setMargins(6, 6, 6, 6)
            }
            setOnClickListener { onClick() }
        }
    }

    // ==========================================
    // 3. EXPAND / COLLAPSE (BATTERY OPTIMIZER)
    // ==========================================
    private fun collapseDeck(params: WindowManager.LayoutParams) {
        isDeckExpanded = false
        fullDeckView.visibility = View.GONE
        miniBubbleView.visibility = View.VISIBLE
        windowManager.updateViewLayout(overlayContainer, params)
    }

    private fun expandDeck(params: WindowManager.LayoutParams) {
        isDeckExpanded = true
        miniBubbleView.visibility = View.GONE
        fullDeckView.visibility = View.VISIBLE
        windowManager.updateViewLayout(overlayContainer, params)
    }

    // ==========================================
    // 4. SPEECH RECOGNITION & ACTIONS
    // ==========================================
    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        outputTextView.text = "👂 Listening... Speak now."
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        outputTextView.text = "⚡ Processing voice command..."
                    }
                    override fun onError(error: Int) {
                        outputTextView.text = "Voice Input Error ($error). Tap Voice to retry."
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull().orEmpty()
                        if (text.isNotBlank()) {
                            inputEditText.setText(text)
                            processAction(text, null)
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
        }
        speechRecognizer?.startListening(intent)
    }

    // ==========================================
    // 5. CORE ACTION PROCESSOR & TTS
    // ==========================================
    private fun processAction(userPrompt: String, screenBitmap: Bitmap?) {
        serviceScope.launch {
            outputTextView.text = "🧠 JAI Thinking: \"$userPrompt\"..."

            // 1. Send Query to Gemini Multi-Modal Engine
            val response = withContext(Dispatchers.IO) {
                AiScreenAnalyzer.analyzeScreenImage(screenBitmap, userPrompt)
            }

            // 2. Intercept and Execute Android Native OS Commands
            val isActionExecuted = JaiAgentService.executeCommand(applicationContext, response)

            if (isActionExecuted) {
                outputTextView.text = "✅ Action Executed: $response"
                speakTts("Executing action.")
            } else {
                // 3. Regular Conversation / Speech
                outputTextView.text = response
                speakTts(response)
            }
        }
    }

    private fun speakTts(message: String) {
        if (isTtsReady && message.isNotBlank()) {
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "JAI_TTS_ID")
        }
    }

    // ==========================================
    // 6. TOUCH DRAG LISTENER
    // ==========================================
    @SuppressLint("ClickableViewAccessibility")
    private fun enableTouchDrag(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener(object : View.OnTouchListener {
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
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(overlayContainer, params)
                        return true
                    }
                }
                return false
            }
        })
    }

    override fun onDestroy() {
        serviceJob.cancel()
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
        if (::overlayContainer.isInitialized) {
            windowManager.removeView(overlayContainer)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
