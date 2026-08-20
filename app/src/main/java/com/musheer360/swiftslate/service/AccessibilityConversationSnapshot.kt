package com.musheer360.swiftslate.service

import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect

/**
 * Copies an accessibility tree into detached data. Child nodes are recycled here; the caller
 * owns the root and must recycle it when extraction is complete.
 */
@Suppress("DEPRECATION")
fun snapshotAccessibilityTree(root: AccessibilityNodeInfo): ConversationNodeSnapshot {
    fun copyMetadata(node: AccessibilityNodeInfo): ConversationNodeSnapshot {
        val bounds = runCatching {
            Rect().also { node.getBoundsInScreen(it) }
        }.getOrNull()
        val usableBounds = bounds?.takeIf { it.bottom > it.top }
        fun readText(value: () -> CharSequence?): String? = runCatching {
            value()?.let { text ->
                text.subSequence(0, minOf(text.length, CONVERSATION_MAX_NODE_FIELD_CHARS)).toString()
            }
        }.getOrNull()

        return ConversationNodeSnapshot(
            text = readText { node.text },
            contentDescription = readText { node.contentDescription },
            viewIdResourceName = runCatching { node.viewIdResourceName }.getOrNull(),
            className = runCatching { node.className?.toString() }.getOrNull(),
            isEditable = runCatching { node.isEditable }.getOrDefault(false),
            isPassword = runCatching { node.isPassword }.getOrDefault(false),
            boundsTop = usableBounds?.top,
            boundsBottom = usableBounds?.bottom
        )
    }

    fun copy(node: AccessibilityNodeInfo, depth: Int, budget: IntArray): ConversationNodeSnapshot {
        if (budget[0] >= CONVERSATION_MAX_NODE_COUNT) {
            return copyMetadata(node)
        }
        if (depth > CONVERSATION_MAX_DEPTH) {
            budget[0]++
            return copyMetadata(node)
        }
        budget[0]++
        val children = ArrayList<ConversationNodeSnapshot>()
        val childCount = runCatching { node.childCount }.getOrDefault(0)
        for (index in 0 until childCount) {
            if (budget[0] >= CONVERSATION_MAX_NODE_COUNT) break
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                children += copy(child, depth + 1, budget)
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }
        return copyMetadata(node).copy(children = children)
    }

    return copy(root, 0, intArrayOf(0))
}
