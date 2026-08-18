package com.jai.agent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator

class ScanOverlayCanvas(
    context: Context,
    private val onRegionSelected: (RectF?) -> Unit
) : View(context) {

    private var scanLineY = 0f
    private var selectedPoint: PointF? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val laserGlow = Paint(Paint.ANTI_ALIAS_FLAG)

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200
        interpolator = LinearInterpolator()
        addUpdateListener {
            scanLineY = it.animatedFraction * height
            invalidate()
        }
    }

    init {
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw translucent dark freeze layer
        canvas.drawColor(Color.parseColor("#44000000"))

        // Draw glowing scanner wave
        laserGlow.shader = LinearGradient(
            0f, scanLineY - 60, 0f, scanLineY,
            Color.TRANSPARENT, Color.parseColor("#00E5FF"), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, scanLineY - 60, width.toFloat(), scanLineY, laserGlow)

        paint.color = Color.parseColor("#00E5FF")
        paint.strokeWidth = 6f
        canvas.drawLine(0f, scanLineY, width.toFloat(), scanLineY, paint)

        // Draw user pinpoint target if tapped
        selectedPoint?.let { pt ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            canvas.drawCircle(pt.x, pt.y, 70f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#8000E5FF")
            canvas.drawCircle(pt.x, pt.y, 16f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            selectedPoint = PointF(event.x, event.y)
            invalidate()
            
            // Define 300x200 focused bounding box around tap
            val box = RectF(event.x - 150, event.y - 100, event.x + 150, event.y + 100)
            postDelayed({ onRegionSelected(box) }, 400)
            return true
        }
        return super.onTouchEvent(event)
    }
}
