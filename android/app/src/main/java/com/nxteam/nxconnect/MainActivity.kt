package com.nxteam.nxconnect

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        layout.addView(TextView(this).apply {
            text = "NX Phone Connect"
            textSize = 26f
            gravity = Gravity.CENTER
        })
        layout.addView(Button(this).apply {
            text = "Share this device"
            setOnClickListener { startActivity(Intent(this@MainActivity, HostActivity::class.java)) }
        })
        layout.addView(Button(this).apply {
            text = "Control another device"
            setOnClickListener { startActivity(Intent(this@MainActivity, ControllerActivity::class.java)) }
        })
        layout.addView(TextView(this).apply {
            text = "Warning: an Android device can view a shared iPhone screen but cannot control it. iOS does not allow remote input. An iPhone can control this Android device."
            setTextColor(Color.rgb(200, 120, 0))
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 0)
        })
        setContentView(layout)
    }
}
