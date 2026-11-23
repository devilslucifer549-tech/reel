package com.example.handscroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Performs the actual swipe. It only listens for "which app came to the front"
 * events – it does not read any screen content.
 */
class SwipeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: SwipeAccessibilityService? = null
        @Volatile var currentPackage: String? = null
        var listener: (() -> Unit)? = null

        val TARGETS = setOf("com.instagram.android")
        fun isTargetActive() = currentPackage in TARGETS
    }

    private val prefs by lazy { Prefs(this) }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" ||
            pkg.contains("inputmethod") || pkg.contains("keyboard")
        ) return
        if (pkg != currentPackage) {
            currentPackage = pkg
            listener?.invoke()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    /** Tap the middle of the screen (single = play/pause, double = like). */
    fun tap(double: Boolean): Boolean {
        if (!prefs.allApps && !isTargetActive()) return false
        val dm = resources.displayMetrics
        val path = Path().apply { moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.45f) }
        val b = GestureDescription.Builder()
        b.addStroke(GestureDescription.StrokeDescription(path, 0, 40))
        if (double) b.addStroke(GestureDescription.StrokeDescription(path, 130, 40))
        return dispatchGesture(b.build(), null, null)
    }

    /** next = true -> next reel (finger swipes up). Returns true if a swipe was sent. */
    fun swipe(next: Boolean): Boolean {
        if (!prefs.allApps && !isTargetActive()) return false
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val from = dm.heightPixels * (if (next) 0.72f else 0.28f)
        val to = dm.heightPixels * (if (next) 0.28f else 0.72f)
        val path = Path().apply { moveTo(x, from); lineTo(x, to) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 220))
            .build()
        return dispatchGesture(gesture, null, null)
    }
}
