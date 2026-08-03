package com.musheer360.swiftslate.ui.processtext

import org.junit.Assert.*
import org.junit.Test

class ProcessTextInputTest {

    @Test
    fun parseSelection_returns_text_and_readOnly_flag() {
        val result = ProcessTextInput.parseSelection("hello", false)
        val selection = result.getOrThrow()
        assertEquals("hello", selection.text)
        assertFalse(selection.readOnly)
    }

    @Test
    fun parseSelection_defaults_readOnly_to_true_when_null() {
        val selection = ProcessTextInput.parseSelection("hello", null).getOrThrow()
        assertTrue(selection.readOnly)
    }

    @Test
    fun parseSelection_rejects_null_text() {
        val ex = assertThrows(RejectedSelectionException::class.java) {
            ProcessTextInput.parseSelection(null, false).getOrThrow()
        }
        assertEquals(Rejection.Missing, ex.rejection)
    }

    @Test
    fun parseSelection_rejects_blank_text() {
        val ex = assertThrows(RejectedSelectionException::class.java) {
            ProcessTextInput.parseSelection("   ", false).getOrThrow()
        }
        assertEquals(Rejection.Missing, ex.rejection)
    }

    @Test
    fun parseSelection_rejects_invisible_only_text() {
        // Zero-width space + bidi format char
        val invisible = "\u200B\u202D"
        val ex = assertThrows(RejectedSelectionException::class.java) {
            ProcessTextInput.parseSelection(invisible, false).getOrThrow()
        }
        assertEquals(Rejection.Missing, ex.rejection)
    }

    @Test
    fun parseSelection_rejects_text_exceeding_max_chars() {
        val long = "a".repeat(ProcessTextInput.MAX_CHARS + 1)
        val ex = assertThrows(RejectedSelectionException::class.java) {
            ProcessTextInput.parseSelection(long, false).getOrThrow()
        }
        assertEquals(Rejection.TooLong, ex.rejection)
    }

    @Test
    fun parseSelection_accepts_text_at_exact_max_chars() {
        val exact = "a".repeat(ProcessTextInput.MAX_CHARS)
        val result = ProcessTextInput.parseSelection(exact, false)
        assertTrue(result.isSuccess)
        assertEquals(ProcessTextInput.MAX_CHARS, result.getOrThrow().text.length)
    }

    @Test
    fun parseSelection_strips_BOM_prefix() {
        val result = ProcessTextInput.parseSelection("\uFEFFhello", false)
        assertEquals("hello", result.getOrThrow().text)
    }

    @Test
    fun parseSelection_normalizes_line_endings() {
        val result = ProcessTextInput.parseSelection("a\r\nb\rc", false)
        assertEquals("a\nb\nc", result.getOrThrow().text)
    }

    @Test
    fun parseSelection_preserves_interior_whitespace() {
        val result = ProcessTextInput.parseSelection("  hello  \n  world  ", false)
        assertEquals("  hello  \n  world  ", result.getOrThrow().text)
    }
}
