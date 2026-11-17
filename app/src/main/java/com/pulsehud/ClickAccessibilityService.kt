package com.pulsehud

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Counts only click events exposed by another app's accessibility tree.
 * Many games draw touch controls on custom surfaces and expose no click events.
 */
class ClickAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() == packageName) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            MonitorState.recordClick(this)
        }
    }

    override fun onInterrupt() = Unit
}
