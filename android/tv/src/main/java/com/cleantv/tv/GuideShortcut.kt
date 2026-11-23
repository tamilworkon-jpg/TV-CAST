package com.cleantv.tv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Remote shortcut: press the Guide button twice quickly to open TV Cast.
 * Only key presses are seen (no screen content); a single Guide press still does what it normally does.
 * Turn on once in Settings → Accessibility → TV Cast shortcut.
 */
class GuideShortcut : AccessibilityService() {
    private var lastGuide = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_GUIDE) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0 && event.eventTime - lastGuide < DOUBLE_PRESS_MS) {
                lastGuide = 0
                consumed = true
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                return true
            }
            return consumed
        }
        // Key up: swallow the one belonging to the second press, remember the first.
        if (consumed) { consumed = false; return true }
        lastGuide = event.eventTime
        return false
    }

    /** The second press of a double press is swallowed (both its down and up). */
    private var consumed = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        private const val DOUBLE_PRESS_MS = 600L
    }
}
