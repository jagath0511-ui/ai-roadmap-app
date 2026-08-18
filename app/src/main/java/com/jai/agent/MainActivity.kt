package com.jai.agent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val btn = Button(this).apply {
            text = "Start JAI Floating Companion"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    val serviceIntent = Intent(this@MainActivity, FloatingOverlayService::class.java)
                    startService(serviceIntent)
                    Toast.makeText(this@MainActivity, "JAI Floating Companion Active!", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
        setContentView(btn)
    }
}
