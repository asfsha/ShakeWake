package com.shakewake

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ShakeWakeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ShakeWakeSvc"

        // Notification
        private const val NOTIFICATION_CHANNEL_ID = "shakewake_service"
        private const val NOTIFICATION_ID = 1

        // Double-press detection
        private const val DOUBLE_PRESS_TIMEOUT_MS = 350L

        // Shared-prefs keys
        private const val PREFS_NAME = "shakewake_prefs"
        private const val KEY_SHAKE_ENABLED = "shake_enabled"

        // Static reference so MainActivity can check running state
        @Volatile
        var isRunning: Boolean = false
            private set

        // Instance reference for live communication
        @Volatile
        var instance: ShakeWakeAccessibilityService? = null
            private set

        /** Check whether our accessibility service is enabled in settings. */
        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val expected = context.packageName + "/" + ShakeWakeAccessibilityService::class.java.canonicalName
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = enabledServices.split(':').iterator()
            while (colonSplitter.hasNext()) {
                val next = colonSplitter.next()
                if (next.equals(expected, ignoreCase = true)) return true
            }
            return false
        }
    }

    // System services
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager
    private val handler = Handler(Looper.getMainLooper())

    // Shake detector
    private var shakeDetector: ShakeDetector? = null
    private var shakeEnabled: Boolean = true

    // ── Volume-key double-press state ────────────────────────────
    private var lastVolumeDownTime = 0L
    private var lastVolumeUpTime = 0L
    private var volumeDownSinglePressRunnable: Runnable? = null
    private var volumeUpSinglePressRunnable: Runnable? = null

    // ── Lifecycle ────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        instance = this

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // Load preferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        shakeEnabled = prefs.getBoolean(KEY_SHAKE_ENABLED, true)

        // Start shake detector
        if (shakeEnabled) startShakeDetector()

        Log.i(TAG, "Service connected")
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        stopShakeDetector()
        volumeDownSinglePressRunnable?.let { handler.removeCallbacks(it) }
        volumeUpSinglePressRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used – we only need key events
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    // ── Key event handling ───────────────────────────────────────

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> return handleVolumeDown()
                KeyEvent.KEYCODE_VOLUME_UP -> return handleVolumeUp()
            }
        }
        // Also consume ACTION_UP for volume keys so the system doesn't process them
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_UP -> return true
            }
        }
        return super.onKeyEvent(event)
    }

    // ── Volume-down double-press ─────────────────────────────────

    private fun handleVolumeDown(): Boolean {
        val now = System.currentTimeMillis()

        if (now - lastVolumeDownTime < DOUBLE_PRESS_TIMEOUT_MS) {
            // Double press – cancel any pending single-press
            volumeDownSinglePressRunnable?.let { handler.removeCallbacks(it) }
            volumeDownSinglePressRunnable = null
            lastVolumeDownTime = 0
            vibrateShort()
            takeScreenshot()
            return true
        }

        // First press – schedule single-press action after timeout
        lastVolumeDownTime = now
        volumeDownSinglePressRunnable = Runnable {
            performNormalVolumeDown()
            volumeDownSinglePressRunnable = null
        }
        handler.postDelayed(volumeDownSinglePressRunnable!!, DOUBLE_PRESS_TIMEOUT_MS)
        return true
    }

    // ── Volume-up double-press ───────────────────────────────────

    private fun handleVolumeUp(): Boolean {
        val now = System.currentTimeMillis()

        if (now - lastVolumeUpTime < DOUBLE_PRESS_TIMEOUT_MS) {
            volumeUpSinglePressRunnable?.let { handler.removeCallbacks(it) }
            volumeUpSinglePressRunnable = null
            lastVolumeUpTime = 0
            vibrateShort()
            lockScreen()
            return true
        }

        lastVolumeUpTime = now
        volumeUpSinglePressRunnable = Runnable {
            performNormalVolumeUp()
            volumeUpSinglePressRunnable = null
        }
        handler.postDelayed(volumeUpSinglePressRunnable!!, DOUBLE_PRESS_TIMEOUT_MS)
        return true
    }

    // ── Normal volume adjustment ─────────────────────────────────

    private fun performNormalVolumeDown() {
        audioManager.adjustVolume(
            AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun performNormalVolumeUp() {
        audioManager.adjustVolume(
            AudioManager.ADJUST_RAISE,
            AudioManager.FLAG_SHOW_UI
        )
    }

    // ── Screenshot ──────────────────────────────────────────────

    private fun takeScreenshot() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> takeScreenshotApi30()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> takeScreenshotViaGlobalAction()
            else -> Toast.makeText(this, R.string.toast_screenshot_api, Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun takeScreenshotViaGlobalAction() {
        try {
            val performed = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            if (!performed) {
                Log.e(TAG, "GLOBAL_ACTION_TAKE_SCREENSHOT failed")
                showToast(R.string.toast_screenshot_failed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception taking screenshot via global action", e)
            showToast(R.string.toast_screenshot_failed)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun takeScreenshotApi30() {
        try {
            val displayId = try {
                val d = this.display
                d?.displayId ?: 0
            } catch (_: Exception) {
                0
            }

            takeScreenshot(displayId, { r -> r.run() }, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val colorSpace = screenshot.colorSpace
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)

                        hardwareBuffer.close()

                        if (bitmap != null) {
                            saveScreenshot(bitmap)
                            bitmap.recycle()
                        } else {
                            showToast(R.string.toast_screenshot_failed)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing screenshot", e)
                        showToast(R.string.toast_screenshot_failed)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed, error code: $errorCode")
                    showToast(R.string.toast_screenshot_failed)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception taking screenshot", e)
            showToast(R.string.toast_screenshot_failed)
        }
    }

    private fun saveScreenshot(bitmap: Bitmap) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "ShakeWake_$timestamp.png"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_PICTURES + "/ShakeWake"
            )
        }

        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        )

        if (uri != null) {
            var stream: OutputStream? = null
            try {
                stream = contentResolver.openOutputStream(uri)
                if (stream != null) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    stream.flush()
                    showToast(R.string.toast_screenshot_saved)
                    Log.i(TAG, "Screenshot saved: $filename")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving screenshot", e)
                showToast(R.string.toast_screenshot_failed)
            } finally {
                stream?.close()
            }
        } else {
            showToast(R.string.toast_screenshot_failed)
        }
    }

    // ── Lock screen ──────────────────────────────────────────────

    private fun lockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // API 28+: Use global action to lock the screen
            val performed = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            if (!performed) {
                Log.w(TAG, "GLOBAL_ACTION_LOCK_SCREEN failed")
            }
        }
    }

    // ── Wake screen on shake ────────────────────────────────────

    private fun wakeScreen() {
        if (!powerManager.isInteractive) {
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "ShakeWake:wakeScreen"
            )
            wakeLock.acquire(3000L) // Auto-release after 3 s
            Log.i(TAG, "Screen woken by shake")
        }
    }

    // ── Shake detector management ────────────────────────────────

    private fun startShakeDetector() {
        if (shakeDetector != null) stopShakeDetector()
        shakeDetector = ShakeDetector(this) { onShake() }
        shakeDetector?.start()
    }

    private fun stopShakeDetector() {
        shakeDetector?.stop()
        shakeDetector = null
    }

    private fun onShake() {
        handler.post {
            if (shakeEnabled && !powerManager.isInteractive) {
                wakeScreen()
            }
        }
    }

    /** Called from MainActivity to toggle shake detection. */
    fun setShakeEnabled(enabled: Boolean) {
        shakeEnabled = enabled
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SHAKE_ENABLED, enabled).apply()

        if (enabled) {
            startShakeDetector()
        } else {
            stopShakeDetector()
        }
    }

    fun isShakeEnabled(): Boolean = shakeEnabled

    // ── Helpers ──────────────────────────────────────────────────

    private fun vibrateShort() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator?
        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        50, android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(50)
            }
        }
    }

    private fun showToast(resId: Int) {
        handler.post { Toast.makeText(this, resId, Toast.LENGTH_SHORT).show() }
    }

}
