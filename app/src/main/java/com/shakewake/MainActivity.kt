package com.shakewake

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    // ── UI references ────────────────────────────────────────────

    private val statusText: TextView by lazy { findViewById(R.id.statusText) }
    private val permAccessibilityBadge: TextView by lazy { findViewById(R.id.permAccessibilityBadge) }
    private val permNotificationBadge: TextView by lazy { findViewById(R.id.permNotificationBadge) }
    private val btnOpenAccessibility: MaterialButton by lazy { findViewById(R.id.btnOpenAccessibility) }
    private val btnToggleShake: MaterialButton by lazy { findViewById(R.id.btnToggleShake) }

    // ── Lifecycle ────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("shakewake_prefs", Context.MODE_PRIVATE)

        btnOpenAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        btnToggleShake.setOnClickListener {
            toggleShakeDetection()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    // ── UI update ────────────────────────────────────────────────

    private fun updateUI() {
        val serviceEnabled = ShakeWakeAccessibilityService.isAccessibilityServiceEnabled(this)
        val serviceRunning = ShakeWakeAccessibilityService.isRunning

        // Status
        if (serviceEnabled && serviceRunning) {
            statusText.text = getString(R.string.status_enabled)
            statusText.setTextColor(getColor(R.color.success))
        } else {
            statusText.text = getString(R.string.status_disabled)
            statusText.setTextColor(getColor(R.color.error))
        }

        // Accessibility permission badge
        if (serviceEnabled) {
            permAccessibilityBadge.text = getString(R.string.perm_granted)
            permAccessibilityBadge.setTextColor(getColor(R.color.success))
        } else {
            permAccessibilityBadge.text = getString(R.string.perm_not_granted)
            permAccessibilityBadge.setTextColor(getColor(R.color.error))
        }

        permNotificationBadge.visibility = TextView.GONE

        // Shake toggle button
        val shakeEnabled = prefs.getBoolean("shake_enabled", true)
        btnToggleShake.text = if (shakeEnabled) {
            getString(R.string.btn_enable_shake)
        } else {
            getString(R.string.btn_disable_shake)
        }

    }

    // ── Actions ──────────────────────────────────────────────────

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback: open general settings
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(
                    this,
                    "Could not open accessibility settings",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun toggleShakeDetection() {
        val currentlyEnabled = prefs.getBoolean("shake_enabled", true)
        val newEnabled = !currentlyEnabled
        prefs.edit().putBoolean("shake_enabled", newEnabled).apply()

        // If service is running, update it live
        ShakeWakeAccessibilityService.instance?.setShakeEnabled(newEnabled)

        updateUI()
    }
}
