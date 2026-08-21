package com.jai.agent

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import kotlin.math.sqrt

class WakeGestureManager(
    private val context: Context,
    private val onWakeTriggered: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var proximitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private var accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Shake detection thresholds
    private var lastAcceleration = 0f
    private var currentAcceleration = 0f
    private var accelerationDrift = 0f
    private var lastTriggerTime = 0L

    fun startListening() {
        proximitySensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        currentAcceleration = SensorManager.GRAVITY_EARTH
        lastAcceleration = SensorManager.GRAVITY_EARTH
    }

    fun stopListening() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        val now = System.currentTimeMillis()
        // 2-second cooldown to avoid continuous double-triggering
        if (now - lastTriggerTime < 2000) return

        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val distance = event.values[0]
                val maxRange = event.sensor.maximumRange
                // Waved over top sensor
                if (distance < maxRange && distance <= 4.0f) {
                    lastTriggerTime = now
                    triggerWake()
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                lastAcceleration = currentAcceleration
                currentAcceleration = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                val delta = currentAcceleration - lastAcceleration
                accelerationDrift = accelerationDrift * 0.9f + delta

                // Vigorous double-shake threshold
                if (accelerationDrift > 14) {
                    lastTriggerTime = now
                    triggerWake()
                }
            }
        }
    }

    private fun triggerWake() {
        wakeUpDisplay()
        onWakeTriggered()
    }

    @Suppress("DEPRECATION")
    private fun wakeUpDisplay() {
        try {
            val isScreenOn = powerManager?.isInteractive ?: true
            if (!isScreenOn) {
                val wakeLock = powerManager?.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "JAI:GestureWakeLock"
                )
                wakeLock?.acquire(3000) // Keep screen awake for 3 seconds while JAI listens
            }
        } catch (e: Exception) {
            FailureLogger.log(context, "WakeGestureManager", "Failed to wake display: ${e.localizedMessage}")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
