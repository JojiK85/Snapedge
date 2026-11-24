package com.snapedge.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Optional AccessibilityService used to perform smooth scroll gestures during hands-free Long Screenshots.
 */
class AutoScrollAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event monitoring required; only gesture dispatch is used on demand.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    suspend fun performScrollDown(screenWidth: Int, screenHeight: Int): Boolean {
        val x = screenWidth * 0.5f
        val startY = screenHeight * 0.72f
        val endY = screenHeight * 0.28f

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 380L))
            .build()

        return suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)

            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    companion object {
        @Volatile
        var instance: AutoScrollAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }
}
