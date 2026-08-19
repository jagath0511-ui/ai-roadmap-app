package com.jai.agent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private val REQUEST_SCREEN_CAPTURE = 1001
    private val REQUEST_PERMISSIONS = 2001
    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Phase 1 Scheduled Action Guild Initialization
        ScheduledDigestWorker.scheduleDailyDigests(this)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(60, 60, 60, 60)
        }

        val title = TextView(this).apply {
            text = "⚡ JAI Omniscient Deck"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 24f
            gravity = Gravity.CENTER
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, 40)
        }

        val launchButton = Button(this).apply {
            text = "Launch JAI Companion"
            setTextColor(Color.parseColor("#0F172A"))
            textSize = 15f
            val shape = GradientDrawable().apply {
                setColor(Color.parseColor("#00E5FF"))
                cornerRadius = 24f
            }
            background = shape
            setPadding(40, 24, 40, 24)
            setOnClickListener {
                checkAllPermissions()
            }
        }

        rootLayout.addView(title)
        rootLayout.addView(launchButton)
        setContentView(rootLayout)
    }

    private fun checkAllPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable 'Display over other apps'", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        val neededPermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_SCREEN_CAPTURE
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            requestScreenCapture()
        }
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
            Toast.makeText(this, "Screen capture required for JAI Vision.", Toast.LENGTH_SHORT).show()
        }
    }
}

