package com.google.android.accessibility.selecttospeak

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * SwiftSlate preview-only compatibility service.
 *
 * Its fully-qualified class name deliberately matches the entry in WeChat's
 * accessibility-service whitelist (`DefaultWhiteServiceList`), so WeChat's
 * `checkHasServiceInList()` string-containment check
 * (`pkg/name`.contains("com.google.android.accessibility.selecttospeak.SelectToSpeakService"))
 * hits and `AccUtil.isAccessibilityEnabled()` returns true, which stops WeChat from
 * clearing/faking its accessibility node tree. It performs no real work.
 */
class SelectToSpeakService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
