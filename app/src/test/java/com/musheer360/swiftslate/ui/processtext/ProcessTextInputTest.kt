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

    // --- The length bound is in UTF-16 code units, not characters ---

    @Test
    fun parseSelection_counts_astral_characters_as_two_units() {
        // MAX_CHARS is documented as UTF-16 code units because that is what the payload costs.
        // An emoji is a surrogate pair, so half as many of them fit as the constant suggests —
        // switching this to codePointCount() would silently double the largest accepted payload.
        val atLimit = "😀".repeat(ProcessTextInput.MAX_CHARS / 2)
        assertEquals(ProcessTextInput.MAX_CHARS, atLimit.length)
        assertTrue(ProcessTextInput.parseSelection(atLimit, false).isSuccess)

        val overLimit = atLimit + "😀"
        val ex = assertThrows(RejectedSelectionException::class.java) {
            ProcessTextInput.parseSelection(overLimit, false).getOrThrow()
        }
        assertEquals(Rejection.TooLong, ex.rejection)
    }

    @Test
    fun parseSelection_surrogate_pairs_are_not_mistaken_for_invisible_text() {
        // Surrogates are CharCategory.SURROGATE, not FORMAT — an emoji-only selection is real
        // content and must not be rejected as blank.
        val result = ProcessTextInput.parseSelection("😀", false)
        assertEquals("😀", result.getOrThrow().text)
    }

    @Test
    fun parseSelection_honours_an_injected_max() {
        assertTrue(ProcessTextInput.parseSelection("hello", false, maxChars = 5).isSuccess)
        val ex = assertThrows(RejectedSelectionException::class.java) {
            ProcessTextInput.parseSelection("hello", false, maxChars = 4).getOrThrow()
        }
        assertEquals(Rejection.TooLong, ex.rejection)
    }

    // --- Normalization happens before the blank check, and only where it should ---

    @Test
    fun parseSelection_rejects_text_that_is_blank_only_after_normalization() {
        // "\r\n" is two characters, so a naive isBlank() on the raw input would pass it through
        // as content and send a lone newline to the model.
        val ex = assertThrows(RejectedSelectionException::class.java) {
            ProcessTextInput.parseSelection("\r\n", false).getOrThrow()
        }
        assertEquals(Rejection.Missing, ex.rejection)
    }

    @Test
    fun parseSelection_collapses_CRLF_before_bare_CR() {
        // Order matters: replacing bare \r first would turn "\r\n" into "\n\n" and double every
        // line break coming from a Windows-style host.
        val result = ProcessTextInput.parseSelection("a\r\r\nb", false)
        assertEquals("a\n\nb", result.getOrThrow().text)
    }

    // Invisible characters stay as \u escapes here for the same reason they do in the class under
    // test: a literal BOM in a source file trips lint's ByteOrderMark check.

    @Test
    fun parseSelection_rejects_a_selection_that_is_only_a_BOM() {
        val ex = assertThrows(RejectedSelectionException::class.java) {
            ProcessTextInput.parseSelection("\uFEFF", false).getOrThrow()
        }
        assertEquals(Rejection.Missing, ex.rejection)
    }

    @Test
    fun parseSelection_strips_only_a_leading_BOM_not_an_interior_one() {
        // An interior U+FEFF is a zero-width no-break space the user actually selected; only the
        // one at the front is an encoding artefact.
        val result = ProcessTextInput.parseSelection("a\uFEFFb", false)
        assertEquals("a\uFEFFb", result.getOrThrow().text)
    }

    @Test
    fun parseSelection_does_not_strip_invisible_characters_from_real_text() {
        // Invisibility decides whether a selection is blank; it is not a sanitizer. The text that
        // goes to the model is what the user selected, zero-width spaces and all.
        val withZwsp = "a\u200Bb"
        assertEquals(withZwsp, ProcessTextInput.parseSelection(withZwsp, false).getOrThrow().text)
    }
}
