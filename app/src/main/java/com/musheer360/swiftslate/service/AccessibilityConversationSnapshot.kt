package com.musheer360.swiftslate.service

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Copies an accessibility tree into detached data. Child nodes are recycled here; the caller
 * owns the root and must recycle it when extraction is complete.
 */
@Suppress("DEPRECATION")
fun snapshotAccessibilityTree(root: AccessibilityNodeInfo): ConversationNodeSnapshot {
    fun copy(node: AccessibilityNodeInfo, depth: Int, budget: IntArray): ConversationNodeSnapshot {
        if (budget[0] >= CONVERSATION_MAX_NODE_COUNT) {
            return ConversationNodeSnapshot(
                text = runCatching { node.text?.toString() }.getOrNull(),
                contentDescription = runCatching { node.contentDescription?.toString() }.getOrNull(),
                viewIdResourceName = runCatching { node.viewIdResourceName }.getOrNull(),
                className = runCatching { node.className?.toString() }.getOrNull(),
                isEditable = runCatching { node.isEditable }.getOrDefault(false),
                isPassword = runCatching { node.isPassword }.getOrDefault(false)
            )
        }
        if (depth > CONVERSATION_MAX_DEPTH) {
            budget[0]++
            return ConversationNodeSnapshot(
                text = runCatching { node.text?.toString() }.getOrNull(),
                contentDescription = runCatching { node.contentDescription?.toString() }.getOrNull(),
                viewIdResourceName = runCatching { node.viewIdResourceName }.getOrNull(),
                className = runCatching { node.className?.toString() }.getOrNull(),
                isEditable = runCatching { node.isEditable }.getOrDefault(false),
                isPassword = runCatching { node.isPassword }.getOrDefault(false)
            )
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
        return ConversationNodeSnapshot(
            text = runCatching { node.text?.toString() }.getOrNull(),
            contentDescription = runCatching { node.contentDescription?.toString() }.getOrNull(),
            viewIdResourceName = runCatching { node.viewIdResourceName }.getOrNull(),
            className = runCatching { node.className?.toString() }.getOrNull(),
            isEditable = runCatching { node.isEditable }.getOrDefault(false),
            isPassword = runCatching { node.isPassword }.getOrDefault(false),
            children = children
        )
    }

    return copy(root, 0, intArrayOf(0))
}
