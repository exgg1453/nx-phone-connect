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
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var startTime = 0L
    private var isDragging = false
    private var isEnding = false
    private var isDispatching = false
    private var currentStroke: GestureDescription.StrokeDescription? = null
    private var strokeEndX = 0f
    private var strokeEndY = 0f
    private var lastSegmentTime = 0L

    private val segmentCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            isDispatching = false
            if (isDragging) {
                dispatchNextSegment()
            }
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            isDispatching = false
            isDragging = false
            isEnding = false
            currentStroke = null
        }
    }

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
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val x = horizontal * metrics.widthPixels
            val y = vertical * metrics.heightPixels
            when (phase) {
                "began" -> {
                    startX = x
                    startY = y
                    lastX = x
                    lastY = y
                    startTime = SystemClock.elapsedRealtime()
                    isDragging = false
                    isEnding = false
                    currentStroke = null
                }
                "moved" -> {
                    lastX = x
                    lastY = y
                    startDraggingIfNeeded()
                    if (isDragging) {
                        dispatchNextSegment()
                    }
                }
                "ended" -> {
                    lastX = x
                    lastY = y
                    startDraggingIfNeeded()
                    if (isDragging) {
                        isEnding = true
                        dispatchNextSegment()
                    } else {
                        performTap()
                    }
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

    private fun startDraggingIfNeeded() {
        if (isDragging || currentStroke != null) {
            return
        }
        if (abs(lastX - startX) > MOVEMENT_THRESHOLD || abs(lastY - startY) > MOVEMENT_THRESHOLD) {
            isDragging = true
            strokeEndX = startX
            strokeEndY = startY
            lastSegmentTime = startTime
        }
    }

    private fun dispatchNextSegment() {
        if (isDispatching) {
            return
        }
        val finishing = isEnding
        var targetX = lastX
        val targetY = lastY
        if (targetX == strokeEndX && targetY == strokeEndY) {
            if (!finishing) {
                return
            }
            targetX += 1f
        }
        val path = Path()
        path.moveTo(strokeEndX, strokeEndY)
        path.lineTo(targetX, targetY)

        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastSegmentTime
        val duration = if (currentStroke == null) {
            elapsed.coerceIn(MINIMUM_SEGMENT_DURATION, MAXIMUM_FIRST_SEGMENT_DURATION)
        } else {
            elapsed.coerceIn(MINIMUM_SEGMENT_DURATION, MAXIMUM_SEGMENT_DURATION)
        }
        lastSegmentTime = now

        val stroke = currentStroke?.continueStroke(path, 0, duration, !finishing)
            ?: GestureDescription.StrokeDescription(path, 0, duration, !finishing)
        strokeEndX = targetX
        strokeEndY = targetY
        currentStroke = if (finishing) null else stroke
        if (finishing) {
            isDragging = false
            isEnding = false
        }
        isDispatching = true
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        if (!dispatchGesture(gesture, segmentCallback, mainHandler)) {
            isDispatching = false
            isDragging = false
            currentStroke = null
        }
    }

    private fun performTap() {
        val elapsed = SystemClock.elapsedRealtime() - startTime
        val duration = if (elapsed < LONG_PRESS_THRESHOLD) TAP_DURATION else elapsed.coerceAtMost(MAXIMUM_PRESS_DURATION)
        val path = Path()
        path.moveTo(startX, startY)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile
        var instance: RemoteControlAccessibilityService? = null

        private const val MOVEMENT_THRESHOLD = 12f
        private const val TAP_DURATION = 40L
        private const val MAXIMUM_PRESS_DURATION = 5000L
        private const val LONG_PRESS_THRESHOLD = 400L
        private const val MINIMUM_SEGMENT_DURATION = 16L
        private const val MAXIMUM_SEGMENT_DURATION = 100L
        private const val MAXIMUM_FIRST_SEGMENT_DURATION = 3000L
    }
}
