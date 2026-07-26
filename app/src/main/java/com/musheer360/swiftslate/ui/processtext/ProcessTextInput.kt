package com.musheer360.swiftslate.ui.processtext

data class Selection(val text: String, val readOnly: Boolean)

sealed interface Rejection {
    /** Extra absent, or blank/invisible-only after normalization. */
    data object Missing : Rejection
    /** Longer than the client-side sanity limit. */
    data object TooLong : Rejection
}

class RejectedSelectionException(val rejection: Rejection) : Exception()

/**
 * Parses and validates the two extras an ACTION_PROCESS_TEXT intent delivers. Pure: its
 * entire input is those two values — no Context, no prefs, no CommandManager.
 */
object ProcessTextInput {

    /** Client-side sanity bound in UTF-16 code units — what the payload actually costs. */
    const val MAX_CHARS = 20_000

    /**
     * @param rawText Intent.EXTRA_PROCESS_TEXT as delivered (may be null, often a Spanned).
     * @param readOnlyExtra EXTRA_PROCESS_TEXT_READONLY; null defaults to read-only, since
     *   absence signals a host that never opted into in-place editing.
     */
    fun parseSelection(
        rawText: CharSequence?,
        readOnlyExtra: Boolean?,
        maxChars: Int = MAX_CHARS
    ): Result<Selection> {
        if (rawText == null) return reject(Rejection.Missing)

        // Flatten once: EXTRA_PROCESS_TEXT frequently arrives as a Spanned, and carrying
        // host-specific span classes into equality checks or the model prompt helps nobody.
        var s = rawText.toString()
        // Some hosts prepend a BOM / zero-width no-break space.
        s = s.removePrefix("﻿")
        // Normalize line endings so payloads are consistent across hosts and the model does
        // not appear to have "changed" text merely by echoing \n back.
        s = s.replace("\r\n", "\n").replace('\r', '\n')

        // Blankness is tested on the trimmed form, but the untrimmed text is what gets sent:
        // interior newlines and indentation are meaningful content (paragraphs, code blocks),
        // and the accessibility path does not collapse them either.
        if (s.trim().all { it.isWhitespace() || isInvisible(it) }) return reject(Rejection.Missing)

        if (s.length > maxChars) return reject(Rejection.TooLong)

        return Result.success(Selection(text = s, readOnly = readOnlyExtra ?: true))
    }

    /** Zero-width and bidi/format characters: visually nothing, so not meaningful content. */
    private fun isInvisible(c: Char): Boolean =
        c == '​' || c == '‌' || c == '‍' || c == '⁠' ||
            c == '­' || c == '﻿' ||
            c.category == CharCategory.FORMAT

    private fun reject(r: Rejection): Result<Selection> =
        Result.failure(RejectedSelectionException(r))
}
