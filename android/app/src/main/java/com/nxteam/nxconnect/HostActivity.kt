package com.nxteam.nxconnect

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class HostActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var codeView: TextView
    private lateinit var accessibilityView: TextView
    private lateinit var toggleButton: Button
    private var pairingCode = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        statusView = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER }
        codeView = TextView(this).apply { textSize = 40f; gravity = Gravity.CENTER }
        accessibilityView = TextView(this).apply { gravity = Gravity.CENTER }
        toggleButton = Button(this).apply { setOnClickListener { toggleSharing() } }
        val settingsButton = Button(this).apply {
            text = "Open accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        layout.addView(statusView)
        layout.addView(codeView)
        layout.addView(accessibilityView)
        layout.addView(settingsButton)
        layout.addView(toggleButton)
        setContentView(layout)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val running = HostService.isRunning
        statusView.text = if (running) "Sharing this device" else "Not sharing"
        codeView.text = if (running) pairingCode else ""
        toggleButton.text = if (running) "Stop sharing" else "Start sharing"
        accessibilityView.text = if (RemoteControlAccessibilityService.instance != null) {
            "Remote control is enabled"
        } else {
            "Remote control is off. Enable NX Phone Connect in accessibility settings so a controller can tap and swipe."
        }
    }

    private fun toggleSharing() {
        if (HostService.isRunning) {
            startService(Intent(this, HostService::class.java).setAction(HostService.ACTION_STOP))
            HostService.isRunning = false
            refresh()
            return
        }
        pairingCode = String.format("%06d", (0..999_999).random())
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PROJECTION && resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, HostService::class.java)
                .putExtra(HostService.EXTRA_PAIRING_CODE, pairingCode)
                .putExtra(HostService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(HostService.EXTRA_PERMISSION_DATA, data)
            startForegroundService(intent)
            HostService.isRunning = true
            refresh()
        }
    }

    companion object {
        private const val REQUEST_PROJECTION = 1
        private const val REQUEST_NOTIFICATIONS = 2
    }
}
