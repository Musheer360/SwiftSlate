package com.musheer360.swiftslate.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [FocusedEditableFinder] against a fake tree, since the real
 * [android.view.accessibility.AccessibilityNodeInfo] is Binder-backed and unavailable under the
 * JVM. The fake mirrors the one thing that matters here: accessors can throw once a node is
 * stale, and `recycle()` records whether the walk cleaned up after itself.
 */
private class FakeNode(
    val name: String,
    override val isEditable: Boolean = false,
    override val isFocused: Boolean = false,
    val children: MutableList<FakeNode> = mutableListOf(),
    val throwOnChildAccess: Boolean = false,
) : FocusNode {
    var recycled = false
        private set

    override val childCount: Int
        get() {
            if (throwOnChildAccess) throw IllegalStateException("stale node: $name")
            return children.size
        }

    override fun getChild(index: Int): FocusNode? {
        if (throwOnChildAccess) throw IllegalStateException("stale node: $name")
        return children.getOrNull(index)
    }

    override fun recycle() {
        recycled = true
    }
}

class FocusedEditableFinderTest {

    @Test
    fun matchAtRoot_returnsTheRootWithoutRecycling() {
        val root = FakeNode("root", isEditable = true, isFocused = true)
        assertSame(root, FocusedEditableFinder.find(root))
        assertFalse(root.recycled)
    }

    @Test
    fun deepMatch_returnsTheLeafAndRecyclesItsAncestors() {
        val leaf = FakeNode("leaf", isEditable = true, isFocused = true)
        val mid = FakeNode("mid", children = mutableListOf(leaf))
        val root = FakeNode("root", children = mutableListOf(mid))

        assertSame(leaf, FocusedEditableFinder.find(root))
        assertTrue(root.recycled)
        assertTrue(mid.recycled)
        assertFalse(leaf.recycled)
    }

    @Test
    fun miss_recyclesEveryVisitedNode() {
        val grandchild = FakeNode("grandchild")
        val left = FakeNode("left", children = mutableListOf(grandchild))
        val right = FakeNode("right")
        val root = FakeNode("root", children = mutableListOf(left, right))

        assertNull(FocusedEditableFinder.find(root))
        assertTrue(root.recycled)
        assertTrue(left.recycled)
        assertTrue(right.recycled)
        assertTrue(grandchild.recycled)
    }

    @Test
    fun budgetCap_stopsBeforeAChainLongerThanTheBudget() {
        // One node past the budget, with the only match at the far end of a linear chain.
        val chain = (0..FocusedEditableFinder.NODE_BUDGET).map { i ->
            val last = i == FocusedEditableFinder.NODE_BUDGET
            FakeNode("n$i", isEditable = last, isFocused = last)
        }
        for (i in 0 until chain.size - 1) chain[i].children.add(chain[i + 1])

        assertNull(FocusedEditableFinder.find(chain.first()))
    }

    @Test
    fun depthCap_stopsBeforeAMatchBeyondMaxDepth() {
        val chain = (0..FocusedEditableFinder.MAX_DEPTH + 1).map { i ->
            val last = i == FocusedEditableFinder.MAX_DEPTH + 1
            FakeNode("n$i", isEditable = last, isFocused = last)
        }
        for (i in 0 until chain.size - 1) chain[i].children.add(chain[i + 1])

        assertNull(FocusedEditableFinder.find(chain.first()))
    }

    @Test
    fun exceptionMidWalk_recyclesEveryNodeAlreadyVisited() {
        val healthy = FakeNode("healthy")
        val stale = FakeNode(
            "stale",
            throwOnChildAccess = true,
            children = mutableListOf(FakeNode("unreachable-child")),
        )
        val root = FakeNode("root", children = mutableListOf(healthy, stale))

        // stale.childCount throws mid-walk; the walk must not crash and must not leak the
        // nodes it already obtained.
        assertNull(FocusedEditableFinder.find(root))
        assertTrue(root.recycled)
        assertTrue(healthy.recycled)
        assertTrue(stale.recycled)
    }
}
