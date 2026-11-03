package com.nxteam.nxconnect

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

class RemoteControlAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var startHorizontal = 0f
    private var startVertical = 0f
    private var lastHorizontal = 0f
    private var lastVertical = 0f
    private var startTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun handleTouch(phase: String, horizontal: Float, vertical: Float) {
        mainHandler.post {
            when (phase) {
                "began" -> {
                    startHorizontal = horizontal
                    startVertical = vertical
                    lastHorizontal = horizontal
                    lastVertical = vertical
                    startTime = SystemClock.elapsedRealtime()
                }
                "moved" -> {
                    lastHorizontal = horizontal
                    lastVertical = vertical
                }
                "ended" -> {
                    lastHorizontal = horizontal
                    lastVertical = vertical
                    performGesture()
                }
            }
        }
    }

    fun handleKey(name: String) {
        mainHandler.post {
            when (name) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
        }
    }

    private fun performGesture() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val startX = startHorizontal * metrics.widthPixels
        val startY = startVertical * metrics.heightPixels
        val endX = lastHorizontal * metrics.widthPixels
        val endY = lastVertical * metrics.heightPixels
        val elapsed = SystemClock.elapsedRealtime() - startTime
        val moved = abs(endX - startX) > MOVEMENT_THRESHOLD || abs(endY - startY) > MOVEMENT_THRESHOLD

        val path = Path()
        path.moveTo(startX, startY)
        if (moved) {
            path.lineTo(endX, endY)
        }
        val duration = when {
            moved -> elapsed.coerceIn(MINIMUM_DURATION, MAXIMUM_DURATION)
            elapsed < LONG_PRESS_THRESHOLD -> MINIMUM_DURATION
            else -> elapsed.coerceAtMost(MAXIMUM_DURATION)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile
        var instance: RemoteControlAccessibilityService? = null

        private const val MOVEMENT_THRESHOLD = 12f
        private const val MINIMUM_DURATION = 50L
        private const val MAXIMUM_DURATION = 5000L
        private const val LONG_PRESS_THRESHOLD = 400L
    }
}
