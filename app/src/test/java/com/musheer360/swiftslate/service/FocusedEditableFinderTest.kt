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
    isEditable: Boolean = false,
    isFocused: Boolean = false,
    isPassword: Boolean = false,
    val children: MutableList<FakeNode> = mutableListOf(),
    val throwOnChildAccess: Boolean = false,
    val throwOnIsEditable: Boolean = false,
    val throwOnIsFocused: Boolean = false,
    val throwOnIsPassword: Boolean = false,
) : FocusNode {
    private val editableValue = isEditable
    private val focusedValue = isFocused
    private val passwordValue = isPassword
    var recycled = false
        private set

    override val isEditable: Boolean
        get() {
            if (throwOnIsEditable) throw IllegalStateException("stale isEditable: $name")
            return editableValue
        }

    override val isFocused: Boolean
        get() {
            if (throwOnIsFocused) throw IllegalStateException("stale isFocused: $name")
            return focusedValue
        }

    override val isPassword: Boolean
        get() {
            if (throwOnIsPassword) throw IllegalStateException("stale isPassword: $name")
            return passwordValue
        }

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
        // Note: depth also caps at 32, so a deep chain hits depth first. To isolate budget,
        // use a wide shallow tree where all children are at depth 1.
        val root = FakeNode("root")
        repeat(FocusedEditableFinder.NODE_BUDGET + 1) { i ->
            val isLast = i == FocusedEditableFinder.NODE_BUDGET
            root.children.add(FakeNode("n$i", isEditable = isLast, isFocused = isLast))
        }
        assertNull(FocusedEditableFinder.find(root))
        // Verify no leak — root and all visited children must be recycled on miss.
        assertTrue(root.recycled)
        for (i in 0 until FocusedEditableFinder.NODE_BUDGET + 1) assertTrue("n$i not recycled", root.children[i].recycled)
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

    @Test
    fun isEditableThrows_recyclesNode() {
        val throwing = FakeNode("throwEditable", throwOnIsEditable = true, isFocused = true)
        val root = FakeNode("root", children = mutableListOf(throwing))
        assertNull(FocusedEditableFinder.find(root))
        assertTrue(root.recycled)
        assertTrue(throwing.recycled)
    }

    @Test
    fun isFocusedThrows_recyclesNode() {
        val throwing = FakeNode("throwFocused", isEditable = true, throwOnIsFocused = true)
        val root = FakeNode("root", children = mutableListOf(throwing))
        assertNull(FocusedEditableFinder.find(root))
        assertTrue(root.recycled)
        assertTrue(throwing.recycled)
    }

    @Test
    fun isPasswordThrows_recyclesNode() {
        val throwing = FakeNode("throwPassword", throwOnIsPassword = true, isEditable = true, isFocused = true)
        val root = FakeNode("root", children = mutableListOf(throwing))
        assertNull(FocusedEditableFinder.find(root))
        assertTrue(root.recycled)
        assertTrue(throwing.recycled)
    }

    @Test
    fun getChildReturnsNull_skipsAndFindsSibling() {
        val leaf = FakeNode("leaf", isEditable = true, isFocused = true)
        val holeParent = object : FocusNode {
            override val isEditable = false
            override val isFocused = false
            override val isPassword = false
            override val childCount = 2
            var recycled = false
            override fun getChild(index: Int): FocusNode? = if (index == 0) null else leaf
            override fun recycle() { recycled = true }
        }
        val customRoot = object : FocusNode {
            override val isEditable = false
            override val isFocused = false
            override val isPassword = false
            override val childCount = 1
            var recycled = false
            override fun getChild(index: Int): FocusNode? = holeParent
            override fun recycle() { recycled = true }
        }
        assertSame(leaf, FocusedEditableFinder.find(customRoot))
        assertFalse(leaf.recycled)
    }

    @Test
    fun wideTree_respectsBudgetAndFindsMatch() {
        val match = FakeNode("match", isEditable = true, isFocused = true)
        val root = FakeNode("root", children = mutableListOf())
        repeat(100) { root.children.add(FakeNode("noise$it")) }
        root.children.add(match)
        assertSame(match, FocusedEditableFinder.find(root))
        assertFalse(match.recycled)
    }

    @Test
    fun passwordNode_isNotReturnedEvenIfEditableAndFocused() {
        val password = FakeNode("pwd", isEditable = true, isFocused = true, isPassword = true)
        val root = FakeNode("root", children = mutableListOf(password))
        assertNull(FocusedEditableFinder.find(root))
        assertTrue(root.recycled)
        assertTrue(password.recycled)
    }
}
