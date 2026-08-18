package com.jai.agent

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(60, 60, 60, 60)
        }

        val title = TextView(this).apply {
            text = "⚡ JAI Agent"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 26f
            gravity = Gravity.CENTER
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, 40)
        }

        val launchButton = Button(this).apply {
            text = "Start JAI Floating Companion"
            setTextColor(Color.parseColor("#0F172A"))
            textSize = 15f
            val shape = GradientDrawable().apply {
                setColor(Color.parseColor("#00E5FF"))
                cornerRadius = 24f
            }
            background = shape
            setPadding(40, 24, 40, 24)
            setOnClickListener {
                startOverlaySafely()
            }
        }

        rootLayout.addView(title)
        rootLayout.addView(launchButton)
        setContentView(rootLayout)
    }

    private fun startOverlaySafely() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable 'Display over other apps'", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        try {
            val serviceIntent = Intent(this, FloatingOverlayService::class.java)
            startService(serviceIntent)
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Error starting overlay: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
}
