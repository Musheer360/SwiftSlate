package com.musheer360.swiftslate.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.musheer360.swiftslate.api.GeminiClient
import com.musheer360.swiftslate.api.OpenAICompatibleClient
import com.musheer360.swiftslate.manager.CommandManager
import com.musheer360.swiftslate.manager.KeyManager
import com.musheer360.swiftslate.manager.StatsManager
import com.musheer360.swiftslate.model.Command
import com.musheer360.swiftslate.model.CommandType
import com.musheer360.swiftslate.ui.processtext.ProcessTextEdit
import com.musheer360.swiftslate.ui.processtext.ProcessTextReplacementBridge
import com.musheer360.swiftslate.ui.processtext.resolveProcessTextEdit
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.SwiftSlateApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AssistantService : AccessibilityService() {

    private lateinit var keyManager: KeyManager
    private lateinit var commandManager: CommandManager
    private lateinit var statsManager: StatsManager
    private val client = GeminiClient()
    private val openAIClient = OpenAICompatibleClient()
    private val serviceJob = SupervisorJob()
    // Any exception escaping a coroutine launched from the accessibility service would kill
    // the whole process with no UI to crash into (the Settings toggle stays "on" regardless,
    // so the Dashboard just shows the service as inactive). Log and swallow instead.
    private val serviceScope = CoroutineScope(
        serviceJob + Dispatchers.IO +
            CoroutineExceptionHandler { _, e ->
                Log.w(TAG, "uncaught exception in service scope", e)
            }
    )
    private val isProcessing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var triggerLastChars = setOf<Char>()
    private var cachedPrefix = CommandManager.DEFAULT_PREFIX
    private var cachedTranslatePrefix = ""
    @Volatile
    private var currentJob: Job? = null
    private var processingResetRunnable: Runnable? = null
    // Intentionally single-level undo (toggle between current and previous text).
    // Tracks the source node's identity to prevent cross-field undo corruption.
    @Volatile
    private var lastOriginalText: String? = null
    @Volatile
    private var lastUndoSourceId: String? = null
    @Volatile
    private var lastCopiedText: String? = null
    @Volatile
    private var lastReplacedText: String? = null
    @Volatile
    private var lastReplacedAt = 0L
    @Volatile
    private var lastReplacedSource: AccessibilityNodeInfo? = null
    private var verifyRunnable: Runnable? = null
    /** (clipboard, originalClip, ourText) for a paste-fallback restore that has not run yet. */
    private var pendingClipRestore: Triple<android.content.ClipboardManager, ClipData?, String>? = null
    private var lastTriggerRefresh = 0L
    private var watchdogRunnable: Runnable? = null
    private val overlayToast by lazy { OverlayToast(this@AssistantService, handler) }

    /**
     * Stable identity for the field an undo point belongs to.
     *
     * Uses [AccessibilityNodeInfo.hashCode], which the framework overrides to derive from the
     * source node id (accessibility view id + virtual descendant id) and window id — so it is
     * a per-node value, not an identity hash, and it is equal across the successive node
     * instances the same field produces.
     *
     * This deliberately does NOT prefer viewIdResourceName, which it used to: that is the less
     * precise of the two. Sibling fields built from one layout share a resource name, so a
     * RecyclerView of identical rows or a multi-field form collapsed to a single id and undo
     * could be applied to the wrong field — exactly the corruption the check exists to prevent.
     */
    private fun sourceId(source: AccessibilityNodeInfo): String = source.hashCode().toString()

    private companion object {
        const val TAG = "SwiftSlateService"
        const val TRIGGER_REFRESH_INTERVAL_MS = 5_000L
        const val PROCESSING_WATCHDOG_MS = 120_000L
        val SPINNER_FRAMES = arrayOf("◐", "◓", "◑", "◒")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        keyManager = (applicationContext as SwiftSlateApp).keyManager
        commandManager = CommandManager(applicationContext)
        statsManager = StatsManager(applicationContext)
        updateTriggers()
    }

    private fun updateTriggers() {
        cachedPrefix = commandManager.getTriggerPrefix()
        cachedTranslatePrefix = "${cachedPrefix}translate:"
        val cmds = commandManager.getCommands()
        triggerLastChars = cmds.mapNotNull { it.trigger.lastOrNull() }.toSet()
        lastTriggerRefresh = System.currentTimeMillis()
    }

    private fun startWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            if (isProcessing.get()) {
                currentJob?.cancel()
                isProcessing.set(false)
            }
        }
        watchdogRunnable = runnable
        handler.postDelayed(runnable, PROCESSING_WATCHDOG_MS)
    }

    private fun cancelWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun cancelPendingProcessingReset() {
        processingResetRunnable?.let { handler.removeCallbacks(it) }
        processingResetRunnable = null
    }

    private fun scheduleProcessingReset() {
        cancelPendingProcessingReset()
        val runnable = Runnable { isProcessing.set(false) }
        processingResetRunnable = runnable
        if (!handler.postDelayed(runnable, 500)) {
            isProcessing.set(false)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            handleAccessibilityEvent(event)
        } catch (e: Exception) {
            // AccessibilityNodeInfo methods throw IllegalStateException when the underlying
            // view is gone or the node was already recycled by the time we call into it — a
            // real race in the Accessibility API, not something we can fully prevent by
            // checking first. Nothing here ran inside a coroutine, so nothing catches this on
            // its own: an accessibility service has no foreground UI to crash into, so an
            // uncaught exception silently kills the whole service process. The Settings toggle
            // stays "on" (that flag is independent of whether the process is alive), so the
            // user sees the Dashboard go inactive with no error and no way to tell why. See
            // #125 — swallow and drop the event instead of taking the service down with it.
            Log.w(TAG, "dropping accessibility event", e)
            try { event?.source?.safeRecycle() } catch (_: Exception) {}
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        if (event.packageName?.toString() == packageName) return
        if (!::keyManager.isInitialized) return

        val source = event.source ?: return
        if (source.isPassword) {
            source.safeRecycle()
            return
        }
        val text = source.text?.toString() ?: run {
            source.safeRecycle()
            return
        }
        if (handlePendingProcessTextReplacement(event, source, text)) return
        if (isProcessing.get()) {
            source.safeRecycle()
            return
        }
        if (text.isEmpty()) {
            verifyRunnable?.let { handler.removeCallbacks(it) }
            lastReplacedText = null
            val prev = lastReplacedSource
            lastReplacedSource = null
            if (prev != null && prev !== source) {
                prev.safeRecycle()
            }
            source.safeRecycle()
            return
        }

        // Skip events where text matches what we just replaced (prevents IME re-commit race)
        val replaced = lastReplacedText
        if (replaced != null && text == replaced &&
            System.currentTimeMillis() - lastReplacedAt < 1000) {
            source.safeRecycle()
            return
        }

        if (System.currentTimeMillis() - lastTriggerRefresh > TRIGGER_REFRESH_INTERVAL_MS) {
            updateTriggers()
        }

        val lastChar = text[text.length - 1]
        if (!triggerLastChars.contains(lastChar)) {
            if (!lastChar.isLetterOrDigit() || !text.contains(cachedTranslatePrefix)) {
                source.safeRecycle()
                return
            }
        }

        val command = commandManager.findCommand(text) ?: run {
            source.safeRecycle()
            return
        }

        val precedingText = text.substring(0, text.length - command.trigger.length)
        val cleanText = precedingText.trim()

        if (command.trigger.endsWith("undo") && command.isBuiltIn) {
            if (!isProcessing.compareAndSet(false, true)) {
                source.safeRecycle()
                return
            }
            startWatchdog()
            cancelPendingProcessingReset()
            currentJob?.cancel()
            handleUndo(source, cleanText)
            return
        }

        if (command.isBuiltIn && (command.trigger.endsWith("copy") || command.trigger.endsWith("cut") ||
            command.trigger.endsWith("paste") || command.trigger.endsWith("replace"))) {
            if (!isProcessing.compareAndSet(false, true)) {
                source.safeRecycle()
                return
            }
            startWatchdog()
            cancelPendingProcessingReset()
            currentJob?.cancel()
            handleClipboardCommand(source, precedingText, command)
            return
        }

        when (command.type) {
            CommandType.TEXT_REPLACER -> {
                if (!isProcessing.compareAndSet(false, true)) {
                    source.safeRecycle()
                    return
                }
                startWatchdog()
                cancelPendingProcessingReset()
                currentJob?.cancel()
                currentJob = serviceScope.launch {
                    val thisJob = coroutineContext[Job]
                    try {
                        withContext(Dispatchers.Main) {
                            val replacerOk = replaceText(source, precedingText + command.prompt)
                            if (!replacerOk) {
                                // Don't record an undo point, a CONFIRM haptic or a usage stat
                                // for a replacement the field silently refused.
                                performHapticFeedback(HapticFeedbackConstants.REJECT)
                                showToast(getString(R.string.toast_replace_failed))
                            } else {
                                lastOriginalText = precedingText
                                lastUndoSourceId = sourceId(source)
                                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                statsManager.recordUsage(command.trigger)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            showToast(getString(R.string.toast_replace_failed))
                        }
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main) {
                            if (currentJob === thisJob) {
                                cancelWatchdog()
                                scheduleProcessingReset()
                            }
                            recycleIfUnowned(source)
                        }
                    }
                }
            }
            CommandType.AI -> {
                if (cleanText.isEmpty()) {
                    source.safeRecycle()
                    return
                }
                if (!isProcessing.compareAndSet(false, true)) {
                    source.safeRecycle()
                    return
                }
                startWatchdog()
                cancelPendingProcessingReset()
                currentJob?.cancel()
                processCommand(source, cleanText, command)
            }
        }
    }

    private fun handlePendingProcessTextReplacement(
        event: AccessibilityEvent,
        source: AccessibilityNodeInfo,
        afterText: String
    ): Boolean {
        val request = ProcessTextReplacementBridge.current(SystemClock.elapsedRealtime())
            ?: return false
        if (request.sourcePackage != null &&
            event.packageName?.toString() != request.sourcePackage) {
            return false
        }
        val beforeText = event.beforeText?.toString() ?: return false
        val edit = resolveProcessTextEdit(
            beforeText = beforeText,
            afterText = afterText,
            fromIndex = event.fromIndex,
            removedCount = event.removedCount,
            addedCount = event.addedCount,
            request = request
        )
        if (edit == ProcessTextEdit.Unrelated ||
            !ProcessTextReplacementBridge.consume(request)) {
            return false
        }
        if (edit == ProcessTextEdit.Replaced) {
            source.safeRecycle()
            return true
        }

        edit as ProcessTextEdit.Appended
        if (!isProcessing.compareAndSet(false, true)) {
            source.safeRecycle()
            return true
        }
        startWatchdog()
        cancelPendingProcessingReset()
        currentJob = serviceScope.launch {
            val thisJob = coroutineContext[Job]
            try {
                val replaced = replaceText(source, edit.correctedText)
                if (replaced) {
                    lastOriginalText = beforeText
                    lastUndoSourceId = sourceId(source)
                } else {
                    withContext(Dispatchers.Main) {
                        showToast(getString(R.string.toast_replace_failed))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    showToast(getString(R.string.toast_replace_failed))
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (currentJob === thisJob) {
                        cancelWatchdog()
                        scheduleProcessingReset()
                    }
                    recycleIfUnowned(source)
                }
            }
        }
        return true
    }

    private fun processCommand(source: AccessibilityNodeInfo, text: String, command: Command) {
        if (!keyManager.keystoreAvailable) {
            // keys_keystore_error rather than toast_keystore_unavailable: the latter tells the
            // user to reinstall, which destroys every key, command and setting, and does not
            // address the usual cause (the KeyStore key being invalidated by a lock-screen
            // change, where re-adding the keys is enough). Both strings are already localized.
            handler.post { overlayToast.show(getString(R.string.keys_keystore_error)) }
            cancelWatchdog()
            isProcessing.set(false)
            recycleIfUnowned(source)
            return
        }

        currentJob = serviceScope.launch {
            val thisJob = coroutineContext[Job]
            val originalText = text
            var spinnerJob: Job? = null
            try {
                val outcome = withTimeout(90_000) {
                    runTextCommand(
                        applicationContext, keyManager, client, openAIClient,
                        command.prompt, text
                    ) { spinnerJob = startInlineSpinner(source, originalText) }
                }
                // From the first attempt onward the field holds the spinner glyph instead of the
                // user's text, so every outcome below starts by taking it back out. No spinner
                // means no usable key was ever found and the field was never touched — a failed
                // no-op write must not produce a "could not restore your text" prefix.
                val fieldWasAltered = spinnerJob != null
                spinnerJob?.cancelAndJoin()
                spinnerJob = null

                when (outcome) {
                    is CommandOutcome.Success -> {
                        if (!replaceText(source, outcome.text)) {
                            // The field rejected the write. Restore the user's text, and don't
                            // record an undo point or a CONFIRM haptic for text that never landed.
                            replaceText(source, originalText)
                            performHapticFeedback(HapticFeedbackConstants.REJECT)
                            showToast(getString(R.string.toast_replace_failed))
                        } else {
                            lastOriginalText = originalText
                            lastUndoSourceId = sourceId(source)
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            statsManager.recordUsage(command.trigger)
                        }
                    }
                    is CommandOutcome.Refusal -> {
                        replaceText(source, originalText)
                        performHapticFeedback(HapticFeedbackConstants.REJECT)
                        showToast(getString(R.string.error_safety_blocked))
                    }
                    // Nothing was sent, so there is nothing to restore.
                    is CommandOutcome.Unavailable -> showToast(outcome.message)
                    is CommandOutcome.Failure -> {
                        val restoredOk = !fieldWasAltered || replaceText(source, originalText)
                        performHapticFeedback(HapticFeedbackConstants.REJECT)
                        showToast(
                            if (restoredOk) outcome.message
                            else getString(R.string.toast_restore_failed) + "\n" + outcome.message
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                spinnerJob?.cancelAndJoin()
                // If the restore fails the field is left holding the spinner glyph, which
                // matters more to the user than the timeout itself — say so rather than
                // swallowing it (both strings already exist in every locale).
                var restoreFailed = false
                try { restoreFailed = !replaceText(source, originalText) } catch (_: Exception) { restoreFailed = true }
                showToast(
                    if (restoreFailed) getString(R.string.toast_restore_failed) + "\n" + getString(R.string.toast_request_timed_out)
                    else getString(R.string.toast_request_timed_out)
                )
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    spinnerJob?.cancel()
                    // Only restore if this job still owns the field. The watchdog clears
                    // isProcessing as soon as it cancels, so a new command can start while this
                    // handler is still running under NonCancellable — and restoring the old text
                    // then would overwrite what the newer job has already written.
                    if (currentJob === thisJob) {
                        try { replaceText(source, originalText) } catch (_: Exception) {}
                    }
                }
                throw e
            } catch (e: Exception) {
                spinnerJob?.cancelAndJoin()
                // showToast() dismisses any visible toast first, so the previous code's
                // restore-failure toast was destroyed microseconds later by the error toast
                // below — making it unreadable. Combine them instead.
                var restoreFailed = false
                try { restoreFailed = !replaceText(source, originalText) } catch (_: Exception) { restoreFailed = true }
                val mapped = mapErrorMessage(e.message ?: "Unknown error")
                showToast(if (restoreFailed) getString(R.string.toast_restore_failed) + "\n" + mapped else mapped)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (currentJob === thisJob) {
                        cancelWatchdog()
                        scheduleProcessingReset()
                    }
                    spinnerJob?.cancel()
                    recycleIfUnowned(source)
                }
            }
        }
    }

    private fun handleUndo(source: AccessibilityNodeInfo, currentText: String) {
        currentJob = serviceScope.launch {
            val thisJob = coroutineContext[Job]
            try {
                val previousText = lastOriginalText
                val undoId = lastUndoSourceId
                if (previousText == null || undoId != sourceId(source)) {
                    performHapticFeedback(HapticFeedbackConstants.REJECT)
                    showToast(getString(R.string.toast_nothing_to_undo))
                } else if (replaceText(source, previousText)) {
                    // Commit the new undo point only after the write succeeded. Doing it first
                    // meant a silently-failed replace destroyed the saved original text.
                    lastOriginalText = currentText
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                } else {
                    performHapticFeedback(HapticFeedbackConstants.REJECT)
                    showToast(getString(R.string.toast_undo_failed))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast(getString(R.string.toast_undo_failed))
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (currentJob === thisJob) {
                        cancelWatchdog()
                        scheduleProcessingReset()
                    }
                    recycleIfUnowned(source)
                }
            }
        }
    }

    private fun handleClipboardCommand(source: AccessibilityNodeInfo, precedingText: String, command: Command) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        currentJob = serviceScope.launch {
            val thisJob = coroutineContext[Job]
            try {
                val trigger = command.trigger
                when {
                    trigger.endsWith("copy") -> {
                        val textToCopy = precedingText.trim()
                        if (textToCopy.isEmpty()) {
                            performHapticFeedback(HapticFeedbackConstants.REJECT)
                            showToast(getString(R.string.toast_nothing_to_copy))
                        } else {
                            // The success decision must be made OUTSIDE the inner withContext:
                            // return@withContext exits only that lambda, so the success toast
                            // still fired — and since showToast dismisses the previous toast, it
                            // hid the failure message entirely.
                            val wrote = withContext(Dispatchers.Main) {
                                replaceText(source, precedingText, callerOwnsClipboard = true)
                            }
                            if (wrote) {
                                lastCopiedText = textToCopy
                                withContext(Dispatchers.Main) {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("SwiftSlate", textToCopy))
                                }
                                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                showToast(getString(R.string.toast_copied))
                                statsManager.recordUsage(command.trigger)
                            } else {
                                performHapticFeedback(HapticFeedbackConstants.REJECT)
                                showToast(getString(R.string.toast_replace_failed))
                            }
                        }
                    }
                    trigger.endsWith("cut") -> {
                        val textToCut = precedingText.trim()
                        if (textToCut.isEmpty()) {
                            performHapticFeedback(HapticFeedbackConstants.REJECT)
                            showToast(getString(R.string.toast_nothing_to_cut))
                        } else {
                            val wrote = withContext(Dispatchers.Main) {
                                replaceText(source, "", callerOwnsClipboard = true)
                            }
                            if (wrote) {
                                lastCopiedText = textToCut
                                lastOriginalText = precedingText
                                lastUndoSourceId = sourceId(source)
                                withContext(Dispatchers.Main) {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("SwiftSlate", textToCut))
                                }
                                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                showToast(getString(R.string.toast_cut))
                                statsManager.recordUsage(command.trigger)
                            } else {
                                // Never claim "Cut to clipboard" while the text is still there.
                                performHapticFeedback(HapticFeedbackConstants.REJECT)
                                showToast(getString(R.string.toast_replace_failed))
                            }
                        }
                    }
                    trigger.endsWith("paste") -> handlePasteInto(source, precedingText, keepPrefix = precedingText, command = command)
                    trigger.endsWith("replace") -> handlePasteInto(source, precedingText, keepPrefix = "", command = command)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast(getString(R.string.toast_clipboard_failed))
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (currentJob === thisJob) {
                        cancelWatchdog()
                        scheduleProcessingReset()
                    }
                    recycleIfUnowned(source)
                }
            }
        }
    }

    /**
     * Handles `?paste` and `?replace`.
     *
     * [keepPrefix] is the text to keep in front of the pasted content: the text preceding
     * the trigger for `?paste`, empty for `?replace`.
     *
     * Prefers [pasteFromSystemClipboard], which drives the target app's own paste action so
     * the *real* system clipboard is used. The previous implementation read the clipboard
     * itself and fell back to [lastCopiedText], which could never work for text copied in
     * another app: an accessibility service is not the focused window and not the default
     * IME, and framework ClipboardService gates OP_READ_CLIPBOARD on exactly that — so
     * `primaryClip` was always null and `?paste` reported "Clipboard is empty" for anything
     * SwiftSlate had not copied itself. Writing is not gated, which is why `?copy` works and
     * why the fallback below can still stage text on the clipboard.
     */
    private suspend fun handlePasteInto(
        source: AccessibilityNodeInfo,
        precedingText: String,
        keepPrefix: String,
        command: Command
    ) {
        val pasted = pasteFromSystemClipboard(source, keepPrefix)
        if (pasted != null) {
            lastOriginalText = precedingText
            lastUndoSourceId = sourceId(source)
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            statsManager.recordUsage(command.trigger)
            return
        }

        // Either the clipboard is empty or the field ignored ACTION_SET_TEXT / ACTION_PASTE.
        // Fall back to the only clipboard content this service is allowed to know about: what
        // it last copied itself.
        val fallback = lastCopiedText
        if (fallback.isNullOrEmpty()) {
            performHapticFeedback(HapticFeedbackConstants.REJECT)
            showToast(getString(R.string.toast_clipboard_empty))
            return
        }
        if (replaceText(source, keepPrefix + fallback)) {
            lastOriginalText = precedingText
            lastUndoSourceId = sourceId(source)
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            statsManager.recordUsage(command.trigger)
        } else {
            performHapticFeedback(HapticFeedbackConstants.REJECT)
            showToast(getString(R.string.toast_replace_failed))
        }
    }

    /**
     * Strips the trigger, puts the caret after [keepPrefix], and asks the target app to paste.
     * The app performs the read under its own window focus, so this honours text copied
     * anywhere on the device without SwiftSlate ever reading the clipboard.
     *
     * Returns the resulting field text, or null when the field does not offer a paste action,
     * the app refused a step, or the paste landed somewhere unexpected. The field is left
     * untouched in the first case and holding [keepPrefix] in the others, both of which the
     * caller's fallback can recover from.
     */
    private suspend fun pasteFromSystemClipboard(
        source: AccessibilityNodeInfo,
        keepPrefix: String
    ): String? = withContext(Dispatchers.Main) {
        if (!source.refresh()) return@withContext null
        // Check before touching anything: TextView only advertises ACTION_PASTE when the field is
        // editable, has a selection, AND hasPrimaryClip() is true. Without this guard an empty
        // clipboard still got the trigger stripped by the setFieldText below, so the command
        // silently ate the user's "?paste" and then reported the clipboard was empty.
        val pasteSupported = source.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_PASTE
        }
        if (!pasteSupported) return@withContext null
        if (!setFieldText(source, keepPrefix)) return@withContext null
        delay(50)
        if (!source.refresh()) return@withContext null
        // Bail out if the field silently rejected the write, otherwise the paste would land
        // on top of the still-present trigger text.
        if (source.text?.toString() != keepPrefix) return@withContext null

        val caret = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, keepPrefix.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, keepPrefix.length)
        }
        source.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, caret)
        if (!source.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return@withContext null

        delay(100)
        if (!source.refresh()) return@withContext null
        val after = source.text?.toString() ?: return@withContext null
        // Nothing added => empty clipboard or an ignored paste. Not starting with keepPrefix
        // => the app ignored ACTION_SET_SELECTION and pasted somewhere else; treat both as a
        // failure so the caller's fallback can put the field into a known state.
        if (after == keepPrefix || !after.startsWith(keepPrefix)) return@withContext null
        scheduleTextVerification(source, after)
        after
    }

    /**
     * Writes [newText] into [source]. Returns false when the field could not be updated.
     * It previously returned Unit and signalled failure by returning early, which made every
     * failure invisible: handleUndo had already overwritten its saved original text, and the
     * restore paths could not tell a real restore from a silent no-op.
     */
    private suspend fun replaceText(
        source: AccessibilityNodeInfo,
        newText: String,
        // When the caller owns the clipboard after this call (?copy / ?cut), the paste fallback
        // must not restore or clear it — that destroyed the very clip the command just placed.
        callerOwnsClipboard: Boolean = false
    ): Boolean = withContext(Dispatchers.Main) {
        if (!source.refresh()) return@withContext false
        val bundle = Bundle()
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)

        val success = source.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)

        if (success) {
            // Verify the text actually persisted — some apps (Firefox, Google Keep)
            // return true but don't update their internal text state
            delay(100)
            source.refresh()
            val currentText = source.text?.toString()
            if (currentText == newText) {
                scheduleTextVerification(source, newText)
                return@withContext true // Text persisted
            }
            // Text didn't persist, fall through to clipboard fallback
        }

        // Clipboard fallback: select all + paste (goes through app's input pipeline)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val oldClip = clipboard.primaryClip
        val newClip = ClipData.newPlainText("SwiftSlate Result", newText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            newClip.description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(newClip)

        source.refresh()
        if (source.text == null) {
            // We already replaced the clipboard above; bail out without leaving our temp clip
            // (which holds the transformed text) as the user's clipboard.
            if (!callerOwnsClipboard) restoreClipboard(clipboard, oldClip, newText)
            return@withContext false
        }
        val selectAllArgs = Bundle()
        selectAllArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
        selectAllArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, source.text?.length ?: 0)
        source.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs)

        val pasted = source.performAction(AccessibilityNodeInfo.ACTION_PASTE)

        scheduleTextVerification(source, newText)

        if (!callerOwnsClipboard) {
            // Deliberately does NOT touch `source`: scheduleTextVerification recycles the node
            // at +300ms, so source.refresh() here threw IllegalStateException on API < 33.
            // pendingClipRestore lets onInterrupt/onDestroy run this synchronously — both flush
            // the handler, which previously cancelled it and left SwiftSlate's temp clip (the
            // transformed text) as the user's clipboard indefinitely.
            val currentPending = Triple(clipboard, oldClip, newText)
            pendingClipRestore = currentPending
            handler.postDelayed({
                try {
                    restoreClipboard(clipboard, oldClip, newText)
                } catch (_: Exception) {
                } finally {
                    if (pendingClipRestore === currentPending) {
                        pendingClipRestore = null
                    }
                }
            }, 500)
        }
        // Report what the paste action actually returned. Returning an unconditional true here
        // silently defeated every caller's failure check.
        pasted
    }

    /**
     * Puts the user's clipboard back after the paste fallback in [replaceText].
     *
     * [oldClip] is null whenever the read was denied, which is the normal case: framework
     * ClipboardService gates OP_READ_CLIPBOARD on window focus / default-IME status, and an
     * accessibility service is neither. So this cannot verify what is currently on the
     * clipboard, and it cannot restore what was there before.
     *
     * Leaving SwiftSlate's temp clip in place is not an option — it holds the user's
     * transformed text and would be handed to every later paste, and IS_SENSITIVE only
     * applies from API 33. Previously the fallback therefore cleared the clipboard outright,
     * which destroyed whatever the user had copied on every replacement in an app that
     * ignores ACTION_SET_TEXT (Firefox, Google Keep). Restoring [lastCopiedText] instead
     * recovers the most recent clip this service actually knows about, and only falls back
     * to clearing when there is none.
     */
    private fun restoreClipboard(clipboard: ClipboardManager, oldClip: ClipData?, ourText: String) {
        try {
            val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (current != null && current != ourText) return // user copied something newer
            val recovered = oldClip ?: lastCopiedText?.let { ClipData.newPlainText("SwiftSlate", it) }
            clipboard.setPrimaryClip(recovered ?: ClipData.newPlainText("", ""))
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.safeRecycle() {
        try { recycle() } catch (_: Exception) {}
    }

    /** Recycle source only if scheduleTextVerification didn't take ownership. */
    private fun recycleIfUnowned(source: AccessibilityNodeInfo) {
        if (lastReplacedSource !== source) {
            source.safeRecycle()
        }
    }

    private fun scheduleTextVerification(source: AccessibilityNodeInfo, expectedText: String) {
        lastReplacedText = expectedText
        lastReplacedAt = System.currentTimeMillis()
        // Recycle the previous source if it's a different node
        val prev = lastReplacedSource
        if (prev != null && prev !== source) {
            prev.safeRecycle()
        }
        lastReplacedSource = source
        verifyRunnable?.let { handler.removeCallbacks(it) }
        val capturedSource = source
        val runnable = Runnable {
            try {
                if (!capturedSource.refresh()) return@Runnable
                val currentText = capturedSource.text?.toString()
                val isImeClobber = currentText != null && currentText.isNotEmpty() && expectedText.startsWith(currentText)
                if (isImeClobber && currentText != expectedText && currentText.length < expectedText.length) {
                    val bundle = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expectedText)
                    }
                    capturedSource.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                }
            } catch (_: Exception) {
            } finally {
                // Only recycle if this source is still the current one (not replaced by a newer command)
                if (lastReplacedSource === capturedSource) {
                    lastReplacedText = null
                    capturedSource.safeRecycle()
                    lastReplacedSource = null
                }
            }
        }
        verifyRunnable = runnable
        if (!handler.postDelayed(runnable, 300)) {
            lastReplacedText = null
            lastReplacedAt = 0L
            lastReplacedSource = null
        }
    }

    private fun setFieldText(source: AccessibilityNodeInfo, text: String): Boolean {
        if (!source.refresh()) return false
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return source.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    private fun startInlineSpinner(source: AccessibilityNodeInfo, baseText: String): Job {
        return serviceScope.launch(Dispatchers.Main) {
            var frameIndex = 0
            try {
                while (isActive) {
                    if (!setFieldText(source, "$baseText ${SPINNER_FRAMES[frameIndex]}")) break
                    frameIndex = (frameIndex + 1) % SPINNER_FRAMES.size
                    delay(200)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The node was recycled or the view went away mid-request; stop the spinner
                // instead of letting an unhandled exception kill the service. The AI result
                // path still restores the original text. See #125.
                Log.w(TAG, "spinner node went stale; stopping spinner", e)
            }
        }
    }

    /**
     * Runs a paste-fallback clipboard restore that has not fired yet. Both onInterrupt and
     * onDestroy call handler.removeCallbacksAndMessages(null), which cancelled the pending
     * +500ms restore and left SwiftSlate's temp clip (the user's transformed text) on the
     * clipboard for good.
     */
    private fun flushPendingClipRestore() {
        val pending = pendingClipRestore ?: return
        pendingClipRestore = null
        restoreClipboard(pending.first, pending.second, pending.third)
    }

    private fun mapErrorMessage(raw: String): String = getString(ErrorMessages.map(raw))

    private suspend fun showToast(msg: String) = withContext(Dispatchers.Main) {
        overlayToast.show(msg)
    }

    @Suppress("DEPRECATION")
    private fun performHapticFeedback(feedbackType: Int) {
        handler.post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    val vibrator = vibratorManager.defaultVibrator
                    when (feedbackType) {
                        HapticFeedbackConstants.CONFIRM ->
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                        HapticFeedbackConstants.REJECT ->
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    when (feedbackType) {
                        HapticFeedbackConstants.CONFIRM ->
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                        HapticFeedbackConstants.REJECT ->
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    vibrator.vibrate(50)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {
        flushPendingClipRestore()
        isProcessing.set(false)
        currentJob?.cancel()
        serviceJob.cancelChildren()
        handler.removeCallbacksAndMessages(null)
        lastReplacedText = null
        lastReplacedAt = 0L
        lastReplacedSource?.safeRecycle()
        lastReplacedSource = null
        overlayToast.dismiss()
    }

    override fun onDestroy() {
        super.onDestroy()
        flushPendingClipRestore()
        isProcessing.set(false)
        lastReplacedText = null
        lastReplacedAt = 0L
        lastReplacedSource?.safeRecycle()
        lastReplacedSource = null
        handler.removeCallbacksAndMessages(null)
        overlayToast.dismiss()
        serviceScope.cancel()
    }
}
