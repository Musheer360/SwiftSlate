package com.musheer360.swiftslate.ui.processtext

data class Selection(val text: String, val readOnly: Boolean)

sealed interface Rejection {
    /** Extra absent, or blank/invisible-only after normalization. */
    data object Missing : Rejection
}

class RejectedSelectionException(val rejection: Rejection) : Exception()

/**
 * Parses and validates the two extras an ACTION_PROCESS_TEXT intent delivers. Pure: its
 * entire input is those two values -- no Context, no prefs, no CommandManager.
 */
object ProcessTextInput {

    // Invisible characters as \u escapes so none sit literally (as raw bytes) in this file.
    private const val BOM = '\uFEFF' // zero-width no-break space; some hosts prepend it
    private const val ZERO_WIDTH_SPACE = '\u200B'
    private const val ZWNJ = '\u200C'
    private const val ZWJ = '\u200D'
    private const val WORD_JOINER = '\u2060'
    private const val SOFT_HYPHEN = '\u00AD'

    /**
     * @param rawText Intent.EXTRA_PROCESS_TEXT as delivered (may be null, often a Spanned).
     * @param readOnlyExtra EXTRA_PROCESS_TEXT_READONLY; null defaults to read-only, since
     *   absence signals a host that never opted into in-place editing.
     */
    fun parseSelection(
        rawText: CharSequence?,
        readOnlyExtra: Boolean?
    ): Result<Selection> {
        if (rawText == null) return reject(Rejection.Missing)

        // Flatten once: EXTRA_PROCESS_TEXT frequently arrives as a Spanned, and carrying
        // host-specific span classes into equality checks or the model prompt helps nobody.
        var s = rawText.toString()
        s = s.removePrefix(BOM.toString())
        // Normalize line endings so payloads are consistent across hosts and the model does
        // not appear to have "changed" text merely by echoing \n back.
        s = s.replace("\r\n", "\n").replace('\r', '\n')

        // Blankness is tested on the trimmed form, but the untrimmed text is what gets sent:
        // interior newlines and indentation are meaningful content (paragraphs, code blocks),
        // and the accessibility path does not collapse them either.
        if (s.trim().all { it.isWhitespace() || isInvisible(it) }) return reject(Rejection.Missing)

        // No client-side length cap: the accessibility (typed-trigger) path already sends
        // whatever the field contains uncapped, and the provider itself enforces the real
        // limit (its context/token window) and returns a proper error -- see
        // ErrorMessages.map()'s "request too large" / "context_length_exceeded" handling,
        // which both entry points already rely on for an over-length request.
        return Result.success(Selection(text = s, readOnly = readOnlyExtra ?: true))
    }

    /** Zero-width and bidi/format characters: visually nothing, so not meaningful content. */
    private fun isInvisible(c: Char): Boolean =
        c == ZERO_WIDTH_SPACE || c == ZWNJ || c == ZWJ || c == WORD_JOINER ||
            c == SOFT_HYPHEN || c == BOM ||
            c.category == CharCategory.FORMAT

    private fun reject(r: Rejection): Result<Selection> =
        Result.failure(RejectedSelectionException(r))
}
