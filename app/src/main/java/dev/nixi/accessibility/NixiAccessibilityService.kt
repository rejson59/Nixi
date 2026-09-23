package dev.nixi.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.nixi.util.LogBus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Sterowanie ekranem w trybie ręcznym: gesty (tap, swipe), akcje globalne
 * (back/home/recent) i wpisywanie tekstu do aktywnego pola.
 */
class NixiAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: NixiAccessibilityService? = null

        fun isAvailable(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        LogBus.log("accessibility", "połączono")
    }

    override fun onInterrupt() {
        if (instance === this) instance = null
        LogBus.log("accessibility", "przerwano", "warn")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Jedna gest naraz — w tym samym momencie nie da się wysłać dwóch. */
    @Volatile private var gestureBusy = false

    /** Zwraca true, gdy gest został przyjęty do wykonania. */
    fun tap(xPx: Float, yPx: Float): Boolean {
        val path = Path().apply {
            moveTo(xPx, yPx)
            lineTo(xPx + 0.1f, yPx + 0.1f)
        }
        return gesture(GestureDescription.StrokeDescription(path, 0, 70), "tap")
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return gesture(GestureDescription.StrokeDescription(path, 0, durationMs), "swipe")
    }

    fun global(action: Int): Boolean {
        val svc = instance ?: return false
        return svc.performGlobalAction(action)
    }

    fun typeText(text: String): Boolean {
        val svc = instance ?: return false
        val root = svc.rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused == null) {
            root.recycle()
            return false
        }
        val bundle = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        focused.recycle()
        root.recycle()
        return ok
    }

    private fun gesture(stroke: GestureDescription.StrokeDescription, name: String): Boolean {
        val svc = instance ?: return false
        if (gestureBusy || !svc.dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        gestureBusy = false
                        LogBus.log("accessibility.gesture", "$name: wykonano")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        gestureBusy = false
                        // np. użytkownik dotknął ekranu w trakcie gestu
                        LogBus.log("accessibility.gesture", "$name: przerwano", "warn")
                    }
                },
                mainHandler
            )
        ) {
            return false
        }
        // dispatchGesture() zwraca false, gdy usługa nie może przyjąć gestu
        // (np. trwa inny gest) — wcześniej zwracaliśmy tu zawsze true, więc
        // model dostawał potwierdzenie nawet, gdy nic się nie stało.
        gestureBusy = true
        mainHandler.postDelayed({ gestureBusy = false }, 2000)
        return true
    }
}
