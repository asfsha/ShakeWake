package com.shakewake

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.util.Log

/**
 * Detects shake gestures using the accelerometer.
 *
 * The listener stays registered while shake-to-wake is enabled. If the device
 * only exposes a non-wake-up accelerometer, a partial wake lock is held so the
 * CPU can continue receiving sensor events while the screen is off.
 */
class ShakeDetector(
    private val context: Context,
    private val onShakeDetected: () -> Unit
) {

    companion object {
        private const val TAG = "ShakeDetector"
        private const val SHAKE_THRESHOLD_G_FORCE = 2.7f
        private const val MIN_TIME_BETWEEN_SHAKES_MS = 1500L
        private const val SENSOR_DELAY = SensorManager.SENSOR_DELAY_GAME
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Suppress("DEPRECATION")
    private var partialWakeLock: PowerManager.WakeLock? = null

    private var isRunning = false
    private var lastShakeFiredTime = 0L

    fun start() {
        if (isRunning) return

        val sensor = accelerometer
        if (sensor == null) {
            Log.w(TAG, "No accelerometer available")
            return
        }

        isRunning = true
        lastShakeFiredTime = 0L

        if (!sensor.isWakeUpSensor) {
            acquirePartialWakeLock()
        }

        val registered = sensorManager.registerListener(accelListener, sensor, SENSOR_DELAY)
        if (!registered) {
            Log.w(TAG, "Failed to register accelerometer listener")
            isRunning = false
            releasePartialWakeLock()
            return
        }

        Log.d(TAG, "Started")
    }

    fun stop() {
        if (!isRunning) return

        isRunning = false
        sensorManager.unregisterListener(accelListener)
        releasePartialWakeLock()
        Log.d(TAG, "Stopped")
    }

    private fun acquirePartialWakeLock() {
        if (partialWakeLock?.isHeld == true) return
        @Suppress("DEPRECATION")
        partialWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ShakeWake:shakeDetect"
        ).apply { acquire() }
    }

    private fun releasePartialWakeLock() {
        partialWakeLock?.let { if (it.isHeld) it.release() }
        partialWakeLock = null
    }

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val gForce = kotlin.math.sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

            if (gForce < SHAKE_THRESHOLD_G_FORCE) return

            val now = System.currentTimeMillis()
            if (now - lastShakeFiredTime <= MIN_TIME_BETWEEN_SHAKES_MS) return

            lastShakeFiredTime = now
            Log.d(TAG, "Shake detected! gForce=$gForce")
            onShakeDetected()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
}
