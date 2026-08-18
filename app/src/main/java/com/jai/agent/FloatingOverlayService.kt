package com.jai.agent

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingBubble: View? = null
    private var resultCard: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            setupFloatingMascot()
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun setupFloatingMascot() {
        val density = resources.displayMetrics.density
        val sizePx = (65 * density).toInt()

        // Load the shadow familiar mascot image as the floating bubble
        val mascotView = ImageView(this).apply {
            val resId = resources.getIdentifier("jai_mascot", "drawable", packageName)
            if (resId != 0) {
                setImageResource(resId)
            } else {
                setImageResource(android.R.drawable.sym_def_app_icon)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            
            // Subtle neon border glow ring
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
                            showResultCard("⚡ JAI Companion Summoned\n\n• Point & Ask on Screen\n• Autonomous Web Agent Ready\n• 24h Price Monitor Active")
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

    private fun showResultCard(message: String) {
        if (resultCard != null) {
            try {
                windowManager?.removeView(resultCard)
            } catch (ignored: Exception) {}
            resultCard = null
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val shape = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = 28f
                setStroke(2, Color.parseColor("#00E5FF"))
            }
            background = shape
            setPadding(40, 40, 40, 40)
        }

        val title = TextView(this).apply {
            text = "⚡ JAI Shadow Familiar"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 16f
            paint.isFakeBoldText = true
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                500
            )
        }

        val content = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 20, 0, 20)
        }

        val closeBtn = TextView(this).apply {
            text = "✕ Dismiss"
            setTextColor(Color.GRAY)
            textSize = 12f
            setPadding(0, 10, 0, 0)
            setOnClickListener {
                try {
                    windowManager?.removeView(resultCard)
                } catch (ignored: Exception) {}
                resultCard = null
            }
        }

        scroll.addView(content)
        layout.addView(title)
        layout.addView(scroll)
        layout.addView(closeBtn)

        val cardParams = WindowManager.LayoutParams(
            850,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        resultCard = layout
        try {
            windowManager?.addView(resultCard, cardParams)
        } catch (ignored: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (floatingBubble != null) windowManager?.removeView(floatingBubble)
            if (resultCard != null) windowManager?.removeView(resultCard)
        } catch (ignored: Exception) {}
    }
}

