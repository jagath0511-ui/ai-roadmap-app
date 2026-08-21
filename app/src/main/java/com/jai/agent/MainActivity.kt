package com.jai.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnStartOverlay: Button
    private lateinit var btnGrantAccessibility: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Schedule battery-friendly periodic briefings
        ScheduledDigestWorker.schedulePeriodicDigest(this)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }

        val title = TextView(this).apply {
            text = "⚡ JAI Autonomous Agent"
            textSize = 22f
            setPadding(0, 0, 0, 20)
        }
        layout.addView(title)

        statusText = TextView(this).apply {
            text = "Checking permissions..."
            textSize = 14f
            setPadding(0, 0, 0, 30)
        }
        layout.addView(statusText)

        btnGrantAccessibility = Button(this).apply {
            text = "1. Enable Accessibility Control"
            setOnClickListener {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }
        layout.addView(btnGrantAccessibility)

        btnStartOverlay = Button(this).apply {
            text = "2. Start Floating Assistant Deck"
            setOnClickListener {
                startFloatingService()
            }
        }
        layout.addView(btnStartOverlay)

        setContentView(layout)
        requestRequiredPermissions()
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        }
    }

    private fun startFloatingService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Grant overlay permission and tap Start again", Toast.LENGTH_LONG).show()
            return
        }

        val serviceIntent = Intent(this, FloatingOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "JAI Active: Shake phone or wave hand to wake!", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        val hasAccessibility = JaiAgentService.instance != null

        statusText.text = when {
            !hasAccessibility -> "⚠️ Accessibility Service is OFF (Tap button 1)"
            !hasOverlay -> "⚠️ Overlay Permission Missing (Tap button 2)"
            else -> "🟢 JAI Engine Ready & Active"
        }
    }
}
