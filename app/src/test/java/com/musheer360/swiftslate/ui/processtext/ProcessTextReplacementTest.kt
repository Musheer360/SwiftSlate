package com.musheer360.swiftslate.ui.processtext

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessTextReplacementTest {

    private val request = PendingProcessTextReplacement(
        original = "world",
        replacement = "WORLD",
        sourcePackage = "example.host",
        createdAt = 0L
    )

    @Test
    fun `corrects a result appended after the original selection`() {
        val edit = resolveProcessTextEdit(
            beforeText = "hello world!",
            afterText = "hello worldWORLD!",
            fromIndex = 11,
            removedCount = 0,
            addedCount = 5,
            request = request
        )

        assertEquals(ProcessTextEdit.Appended("hello WORLD!"), edit)
    }

    @Test
    fun `accepts a proper selection replacement`() {
        val edit = resolveProcessTextEdit(
            beforeText = "hello world!",
            afterText = "hello WORLD!",
            fromIndex = 6,
            removedCount = 5,
            addedCount = 5,
            request = request
        )

        assertEquals(ProcessTextEdit.Replaced, edit)
    }

    @Test
    fun `uses the event index when the original occurs more than once`() {
        val edit = resolveProcessTextEdit(
            beforeText = "world and world!",
            afterText = "world and worldWORLD!",
            fromIndex = 15,
            removedCount = 0,
            addedCount = 5,
            request = request
        )

        assertEquals(ProcessTextEdit.Appended("world and WORLD!"), edit)
    }

    @Test
    fun `ignores an insertion that does not exactly match the result`() {
        val edit = resolveProcessTextEdit(
            beforeText = "hello world!",
            afterText = "hello worldOTHER!",
            fromIndex = 11,
            removedCount = 0,
            addedCount = 5,
            request = request
        )

        assertEquals(ProcessTextEdit.Unrelated, edit)
    }

    @Test
    fun `ignores an insertion not immediately after the original selection`() {
        val edit = resolveProcessTextEdit(
            beforeText = "world says hello!",
            afterText = "world says helloWORLD!",
            fromIndex = 16,
            removedCount = 0,
            addedCount = 5,
            request = request
        )

        assertEquals(ProcessTextEdit.Unrelated, edit)
    }
}
