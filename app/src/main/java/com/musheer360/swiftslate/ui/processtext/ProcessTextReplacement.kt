package com.musheer360.swiftslate.ui.processtext

import java.util.concurrent.atomic.AtomicReference

internal data class PendingProcessTextReplacement(
    val original: String,
    val replacement: String,
    val sourcePackage: String?,
    val createdAt: Long
)

internal sealed interface ProcessTextEdit {
    data object Unrelated : ProcessTextEdit
    data object Replaced : ProcessTextEdit
    data class Appended(val correctedText: String) : ProcessTextEdit
}

/** One result awaiting application by the app that launched ACTION_PROCESS_TEXT. */
internal object ProcessTextReplacementBridge {
    private const val MAX_AGE_MS = 3_000L
    private val pending = AtomicReference<PendingProcessTextReplacement?>()

    fun prepare(original: String, replacement: String, sourcePackage: String?, now: Long) {
        pending.set(PendingProcessTextReplacement(original, replacement, sourcePackage, now))
    }

    fun current(now: Long): PendingProcessTextReplacement? {
        val request = pending.get() ?: return null
        if (now - request.createdAt in 0..MAX_AGE_MS) return request
        pending.compareAndSet(request, null)
        return null
    }

    fun consume(request: PendingProcessTextReplacement): Boolean =
        pending.compareAndSet(request, null)
}

/**
 * Distinguishes a proper selection replacement from hosts that append the result after it.
 * AccessibilityEvent indices and counts are UTF-16 offsets, matching Kotlin String indices.
 */
internal fun resolveProcessTextEdit(
    beforeText: String,
    afterText: String,
    fromIndex: Int,
    removedCount: Int,
    addedCount: Int,
    request: PendingProcessTextReplacement
): ProcessTextEdit {
    val original = request.original
    val replacement = request.replacement
    if (fromIndex < 0 || fromIndex > beforeText.length || addedCount != replacement.length) {
        return ProcessTextEdit.Unrelated
    }

    if (removedCount < 0 || fromIndex + removedCount > beforeText.length) {
        return ProcessTextEdit.Unrelated
    }
    val expectedAfter = beforeText.replaceRange(
        fromIndex,
        fromIndex + removedCount,
        replacement
    )
    if (expectedAfter != afterText) return ProcessTextEdit.Unrelated

    if (removedCount == original.length &&
        beforeText.regionMatches(fromIndex, original, 0, original.length)) {
        return ProcessTextEdit.Replaced
    }

    val originalStart = fromIndex - original.length
    if (removedCount == 0 && originalStart >= 0 &&
        beforeText.regionMatches(originalStart, original, 0, original.length)) {
        return ProcessTextEdit.Appended(
            beforeText.replaceRange(originalStart, fromIndex, replacement)
        )
    }

    return ProcessTextEdit.Unrelated
}
