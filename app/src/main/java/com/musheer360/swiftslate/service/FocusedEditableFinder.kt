package com.musheer360.swiftslate.service

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Minimal seam over a node in the accessibility tree so the fallback walk can be unit-tested
 * with a fake tree. The real accessibility API can throw [IllegalStateException] from any
 * accessor when the underlying view has gone away, so implementations are free to throw and
 * [FocusedEditableFinder] must tolerate it.
 */
internal interface FocusNode {
    val isEditable: Boolean
    val isFocused: Boolean
    val isPassword: Boolean
    val childCount: Int

    /** May return null for a recycled/gone child; may throw like the real API. */
    fun getChild(index: Int): FocusNode?

    fun recycle()
}

/**
 * Bounded depth-first search for the node that is both editable and focused.
 *
 * This is the second-stage fallback behind [AccessibilityNodeInfo.findFocus]`(FOCUS_INPUT)`:
 * `findFocus` keeps precedence (it can return container nodes upstream still accepts), and this
 * walk only runs when `findFocus` reports nothing. Some hosts expose the editable input only as
 * a deeper node that `findFocus` does not surface.
 *
 * The walk is deliberately bounded so a pathological or very deep tree cannot spike the main
 * thread: at most [NODE_BUDGET] nodes are visited and the recursion never goes deeper than
 * [MAX_DEPTH]. Every visited node is recycled on the way out — including when a node method
 * throws mid-walk — except the node returned as the match, which the caller takes ownership of.
 *
 * Must be called on the main thread. The underlying [AccessibilityNodeInfo] accessors are
 * Binder IPCs that block on the app's UI thread; calling off the main thread is unsafe and
 * violates the service's threading contract. `remaining` is per-invocation state, so concurrent
 * calls do not share mutable state, but the service already serializes calls via
 * [AssistantService.FOCUS_FALLBACK_MIN_INTERVAL_MS].
 */
internal object FocusedEditableFinder {

    const val NODE_BUDGET = 500
    const val MAX_DEPTH = 32

    /** Must be called on the main thread — see class KDoc. */
    fun find(root: FocusNode): FocusNode? {
        var remaining = NODE_BUDGET

        fun walk(node: FocusNode, depth: Int): FocusNode? {
            // Recycle `node` on every exit except when it is the returned match. The finally
            // block guarantees recycling even when an accessor throws mid-walk.
            var keep = false
            try {
                if (remaining <= 0 || depth > MAX_DEPTH) return null
                remaining--
                // Defense-in-depth: password fields are also editable+focused but must never be
                // returned as a typing target. The caller in AssistantService also checks
                // isPassword after the fallback, but filtering here prevents future reuse from
                // leaking password nodes if that check is ever forgotten.
                if (node.isPassword) {
                    // Fall through to child scan — do not keep this node, recycle in finally.
                } else if (node.isEditable && node.isFocused) {
                    keep = true
                    return node
                }
                val childCount = node.childCount
                for (i in 0 until childCount) {
                    val child = node.getChild(i) ?: continue
                    val found = walk(child, depth + 1)
                    if (found != null) return found
                }
                return null
            } catch (e: Exception) {
                // The node went stale mid-walk. Treat this branch as a miss; `finally` recycles
                // this frame's node and children already visited were recycled in their frames.
                return null
            } finally {
                if (!keep) node.recycle()
            }
        }

        return walk(root, 0)
    }
}

/** Bridges a real [AccessibilityNodeInfo] to [FocusNode] without swallowing its exceptions. */
internal class AccessibilityFocusNode(val node: AccessibilityNodeInfo) : FocusNode {
    override val isEditable: Boolean get() = node.isEditable
    override val isFocused: Boolean get() = node.isFocused
    override val isPassword: Boolean get() = node.isPassword
    override val childCount: Int get() = node.childCount
    override fun getChild(index: Int): FocusNode? =
        node.getChild(index)?.let(::AccessibilityFocusNode)

    override fun recycle() {
        try {
            node.recycle()
        } catch (_: Exception) {
        }
    }
}
