package com.jai.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val REQUEST_SCREEN_CAPTURE = 1001
    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(60, 60, 60, 60)
        }

        val title = TextView(this).apply {
            text = "⚡ JAI Agent Hub"
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
                checkPermissionsAndStart()
            }
        }

        rootLayout.addView(title)
        rootLayout.addView(launchButton)
        setContentView(rootLayout)
    }

    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable 'Display over other apps'", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        // Request Screen Capture permission from Android
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_SCREEN_CAPTURE
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCREEN_CAPTURE && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, FloatingOverlayService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA_INTENT", data)
            }
            startForegroundService(serviceIntent)
            finish()
        } else {
            Toast.makeText(this, "Screen capture permission required for JAI Vision.", Toast.LENGTH_SHORT).show()
        }
    }
}
