package com.jai.agent

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupFloatingBubble()
    }

    private fun setupFloatingBubble() {
        val bubble = TextView(this).apply {
            text = "⚡ JAI"
            setTextColor(Color.WHITE)
            textSize = 14f
            val shape = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = 40f
                setStroke(3, Color.parseColor("#00E5FF"))
            }
            background = shape
            setPadding(32, 20, 32, 20)
        }
        floatingBubble = bubble

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 400
        }

        bubble.setOnTouchListener(object : View.OnTouchListener {
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
                        windowManager?.updateViewLayout(floatingBubble, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = Math.abs(event.rawX - initialTouchX)
                        val diffY = Math.abs(event.rawY - initialTouchY)
                        if (diffX < 15 && diffY < 15) {
                            showResultCard("JAI Companion is ready!\n\n• Point & Ask on Screen\n• Autonomous Browser\n• 24h Price Tracker")
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager?.addView(floatingBubble, params)
    }

    private fun showResultCard(message: String) {
        if (resultCard != null) {
            windowManager?.removeView(resultCard)
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
            text = "⚡ JAI Companion"
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
            text = "✕ Close"
            setTextColor(Color.GRAY)
            textSize = 12f
            setPadding(0, 10, 0, 0)
            setOnClickListener {
                windowManager?.removeView(resultCard)
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
        windowManager?.addView(resultCard, cardParams)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (floatingBubble != null) windowManager?.removeView(floatingBubble)
        if (resultCard != null) windowManager?.removeView(resultCard)
    }
}
