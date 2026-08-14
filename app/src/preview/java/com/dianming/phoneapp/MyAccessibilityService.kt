package com.dianming.phoneapp

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * SwiftSlate preview-only compatibility service.
 *
 * Its fully-qualified class name deliberately matches the second entry in WeChat's
 * accessibility-service whitelist (`DefaultWhiteServiceList`), so WeChat's
 * `checkHasServiceInList()` string-containment check hits. Redundant with
 * [SelectToSpeakService] but cheap insurance if WeChat rotates one whitelist entry.
 */
class MyAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
