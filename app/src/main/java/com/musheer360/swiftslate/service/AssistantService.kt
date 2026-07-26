package com.musheer360.swiftslate.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.musheer360.swiftslate.api.ApiClientUtils
import com.musheer360.swiftslate.api.ApiError
import com.musheer360.swiftslate.api.ApiException
import com.musheer360.swiftslate.api.GeminiClient
import com.musheer360.swiftslate.api.GenerateResult
import com.musheer360.swiftslate.api.OpenAICompatibleClient
import com.musheer360.swiftslate.manager.CommandManager
import com.musheer360.swiftslate.manager.KeyManager
import com.musheer360.swiftslate.manager.StatsManager
import com.musheer360.swiftslate.model.Command
import com.musheer360.swiftslate.model.CommandType
import com.musheer360.swiftslate.model.PrefKeys
import com.musheer360.swiftslate.provider.Providers
import com.musheer360.swiftslate.provider.Transport
import com.musheer360.swiftslate.R
import kotlinx.coroutines.CancellationException
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
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val isProcessing = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var processingStartedAt = 0L
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

    private fun sourceId(source: AccessibilityNodeInfo): String =
        "${source.windowId}:${source.viewIdResourceName ?: source.hashCode()}"

    private companion object {
        const val TRIGGER_REFRESH_INTERVAL_MS = 5_000L
        const val DEFAULT_TEMPERATURE = 0.5
        const val PROCESSING_WATCHDOG_MS = 120_000L
        val SPINNER_FRAMES = arrayOf("◐", "◓", "◑", "◒")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        keyManager = KeyManager(applicationContext)
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
                processingStartedAt = 0L
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
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        if (event.packageName?.toString() == packageName) return
        if (!::keyManager.isInitialized) return

        if (isProcessing.get()) return
        val source = event.source ?: return
        if (source.isPassword) {
            source.safeRecycle()
            return
        }
        val text = source.text?.toString() ?: run {
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
            processingStartedAt = System.currentTimeMillis()
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
            processingStartedAt = System.currentTimeMillis()
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
                processingStartedAt = System.currentTimeMillis()
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
                                processingStartedAt = 0L
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
                processingStartedAt = System.currentTimeMillis()
                startWatchdog()
                cancelPendingProcessingReset()
                currentJob?.cancel()
                processCommand(source, cleanText, command)
            }
        }
    }

    private fun processCommand(source: AccessibilityNodeInfo, text: String, command: Command) {
        if (!keyManager.keystoreAvailable) {
            // keys_keystore_error rather than toast_keystore_unavailable: the latter tells the
            // user to reinstall, which destroys every key, command and setting, and does not
            // address the usual cause (the KeyStore key being invalidated by a lock-screen
            // change, where re-adding the keys is enough). Both strings are already localized.
            handler.post { Toast.makeText(applicationContext, getString(R.string.keys_keystore_error), Toast.LENGTH_LONG).show() }
            cancelWatchdog()
            processingStartedAt = 0L
            isProcessing.set(false)
            recycleIfUnowned(source)
            return
        }

        currentJob = serviceScope.launch {
            val thisJob = coroutineContext[Job]
            val prefs = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val provider = Providers.forType(prefs.getString(PrefKeys.PROVIDER_TYPE, null))
            val model = provider.sanitizeModel(prefs.getString(provider.modelPrefKey, provider.defaultModel))
            val endpoint = provider.resolveEndpoint(prefs.getString(PrefKeys.CUSTOM_ENDPOINT, "") ?: "")

            if (!provider.isConfigured(model, endpoint)) {
                showToast(getString(R.string.toast_custom_not_configured))
                withContext(NonCancellable + Dispatchers.Main) {
                    cancelWatchdog()
                    processingStartedAt = 0L
                    scheduleProcessingReset()
                    recycleIfUnowned(source)
                }
                return@launch
            }
            val temperature = prefs.getFloat(PrefKeys.TEMPERATURE, DEFAULT_TEMPERATURE.toFloat()).toDouble()
            val useStructuredOutput = run {
                val disabledAt = prefs.getLong(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT, 0L)
                System.currentTimeMillis() - disabledAt > 86_400_000L // re-try after 24h
            }

            val originalText = text
            var spinnerJob: Job? = null
            try {
                withTimeout(90_000) {
                    val maxAttempts = keyManager.getKeys().size.coerceAtLeast(1)
                    var lastErrorMsg: String? = null
                    var lastErrorWasRateLimit = false
                    var lastErrorWasPermission = false
                    var lastFailedKey: String? = null
                    var spinnerEverStarted = false
                    val triedKeys = mutableSetOf<String>()
                    var succeeded = false

                    for (attempt in 0 until maxAttempts) {
                        val key = keyManager.getNextKey(triedKeys)
                        if (key == null) break
                        triedKeys.add(key)

                        if (spinnerJob == null) {
                            spinnerJob = startInlineSpinner(source, originalText)
                            spinnerEverStarted = true
                        }

                        val result = when (provider.transport) {
                            Transport.OPENAI_COMPAT -> openAIClient.generate(
                                command.prompt, text, key, model, temperature, endpoint,
                                useStructuredOutput = false,
                                useJsonObjectMode = provider.useJsonObjectMode(useStructuredOutput),
                                extraParams = provider.reasoningParams(model))
                            Transport.GEMINI_NATIVE -> client.generate(
                                command.prompt, text, key, model, temperature, useStructuredOutput,
                                thinkingLevel = provider.thinkingLevel(model))
                        }

                        if (result.isSuccess) {
                            spinnerJob.cancelAndJoin()
                            spinnerJob = null
                            val generateResult = result.getOrThrow()

                            if (ApiClientUtils.isModelRefusal(generateResult.text)) {
                                replaceText(source, originalText)
                                performHapticFeedback(HapticFeedbackConstants.REJECT)
                                showToast(getString(R.string.error_safety_blocked))
                                succeeded = true
                                break
                            }

                            if (!replaceText(source, generateResult.text)) {
                                // The field rejected the write. Restore the user's text (the
                                // spinner glyph is still in it), and don't record an undo point
                                // or a CONFIRM haptic for text that never landed.
                                replaceText(source, originalText)
                                performHapticFeedback(HapticFeedbackConstants.REJECT)
                                showToast(getString(R.string.toast_replace_failed))
                                succeeded = true // suppress the generic failure message below
                                break
                            }
                            lastOriginalText = originalText
                            lastUndoSourceId = sourceId(source)
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            if (generateResult.structuredOutputFailed) {
                                prefs.edit().putLong(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT, System.currentTimeMillis()).apply()
                            }
                            succeeded = true
                            statsManager.recordUsage(command.trigger)
                            break
                        }

                        val msg = result.exceptionOrNull()?.message ?: ""
                        lastErrorMsg = msg
                        val apiError = (result.exceptionOrNull() as? ApiException)?.apiError

                        when (apiError) {
                            is ApiError.RateLimit -> {
                                lastErrorWasRateLimit = true
                                val seconds = apiError.retryAfterSeconds?.toLong() ?: 60
                                keyManager.reportRateLimit(key, seconds)
                            }
                            is ApiError.RequestTooLarge -> {
                                lastErrorWasRateLimit = false
                                break // Fail fast: TPM budget is per org/account, key rotation won't help
                            }
                            is ApiError.InvalidKey -> {
                                lastErrorWasRateLimit = false
                                lastFailedKey = key
                                // Distinguish "this key is bad" from "this key may not use this
                                // model" (both arrive as 401/403) so the final message can be
                                // truthful about which one the user should go fix.
                                val m = msg.lowercase(java.util.Locale.ROOT)
                                lastErrorWasPermission = m.contains("permission") ||
                                    m.contains("does not have access") || m.contains("not been used in project")
                                keyManager.markInvalid(key)
                            }
                            is ApiError.ServerError -> {
                                lastErrorWasRateLimit = false
                                continue // 5xx — try next key
                            }
                            else -> {
                                // Non-retryable. Clear the flag so a 400 arriving after an earlier
                                // 429 is not reported as a rate limit with a bogus countdown.
                                lastErrorWasRateLimit = false
                                break
                            }
                        }
                    }

                    if (!succeeded) {
                        spinnerJob?.cancelAndJoin()
                        spinnerJob = null
                        // Only restore when the field was actually altered: with no usable keys
                        // no spinner ever ran, so a failed no-op write must not produce a
                        // "could not restore your text" prefix.
                        val fieldWasAltered = spinnerEverStarted
                        val restoredOk = !fieldWasAltered || replaceText(source, originalText)
                        performHapticFeedback(HapticFeedbackConstants.REJECT)
                        val restorePrefix = if (restoredOk) "" else getString(R.string.toast_restore_failed) + "\n"
                        // Prefer the message that carries the actual wait time. It used to be
                        // reachable only when nothing had been attempted, so the request that
                        // *discovered* the rate limit showed the vague "Rate limited. Try again
                        // shortly." and only a later request showed the seconds — the useful
                        // message lost the common path. Gated on the last error actually being a
                        // rate limit, so an unrelated failure still reports its own cause even
                        // when some other key happens to be cooling down.
                        val waitMs = keyManager.getShortestWaitTimeMs()
                        if (waitMs != null && (lastErrorMsg == null || lastErrorWasRateLimit)) {
                            val waitSec = ((waitMs + 999) / 1000).coerceAtLeast(1)
                            showToast(restorePrefix + getString(R.string.toast_key_rate_limited, waitSec))
                        } else if (lastErrorWasPermission) {
                            // Must precede the generic branch: lastErrorMsg is never null once a
                            // request was attempted, so this was unreachable below it. A 403 is
                            // usually the selected model not being available to the project
                            // rather than bad keys, so don't send the user to check good keys.
                            showToast(restorePrefix + getString(R.string.error_no_model_access))
                        } else if (lastErrorMsg != null) {
                            val mapped = mapErrorMessage(lastErrorMsg)
                            if (mapped == getString(R.string.error_invalid_key) && keyManager.getKeys().size > 1 && lastFailedKey != null) {
                                val hint = "••••" + lastFailedKey.takeLast(4)
                                showToast(restorePrefix + getString(R.string.error_invalid_key_with_hint, hint))
                            } else {
                                showToast(restorePrefix + mapped)
                            }
                        } else if (keyManager.getKeys().isEmpty()) {
                            showToast(restorePrefix + getString(R.string.toast_no_keys))
                        } else {
                            showToast(restorePrefix + getString(R.string.toast_all_keys_invalid))
                        }
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
                    try { replaceText(source, originalText) } catch (_: Exception) {}
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
                        processingStartedAt = 0L
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
                        processingStartedAt = 0L
                        scheduleProcessingReset()
                    }
                    recycleIfUnowned(source)
                }
            }
        }
    }

    private fun handleClipboardCommand(source: AccessibilityNodeInfo, precedingText: String, command: Command) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
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
                    trigger.endsWith("paste") -> {
                        val pasteText = lastCopiedText ?: clipText
                        if (pasteText.isNullOrEmpty()) {
                            performHapticFeedback(HapticFeedbackConstants.REJECT)
                            showToast(getString(R.string.toast_clipboard_empty))
                        } else {
                            val wrote = withContext(Dispatchers.Main) {
                                replaceText(source, precedingText + pasteText)
                            }
                            if (wrote) {
                                lastOriginalText = precedingText
                                lastUndoSourceId = sourceId(source)
                                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                statsManager.recordUsage(command.trigger)
                            } else {
                                performHapticFeedback(HapticFeedbackConstants.REJECT)
                                showToast(getString(R.string.toast_replace_failed))
                            }
                        }
                    }
                    trigger.endsWith("replace") -> {
                        val pasteText = lastCopiedText ?: clipText
                        if (pasteText.isNullOrEmpty()) {
                            performHapticFeedback(HapticFeedbackConstants.REJECT)
                            showToast(getString(R.string.toast_clipboard_empty))
                        } else {
                            val wrote = withContext(Dispatchers.Main) {
                                replaceText(source, pasteText)
                            }
                            if (wrote) {
                                lastOriginalText = precedingText
                                lastUndoSourceId = sourceId(source)
                                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                statsManager.recordUsage(command.trigger)
                            } else {
                                performHapticFeedback(HapticFeedbackConstants.REJECT)
                                showToast(getString(R.string.toast_replace_failed))
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast(getString(R.string.toast_clipboard_failed))
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (currentJob === thisJob) {
                        cancelWatchdog()
                        processingStartedAt = 0L
                        scheduleProcessingReset()
                    }
                    recycleIfUnowned(source)
                }
            }
        }
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
     * Puts the user's clipboard back after the paste fallback. If [oldClip] is null we could not
     * read the clipboard (from API 29 a non-focused app is denied clipboard reads, so this is the
     * normal case for an accessibility service) — clear it instead, because leaving SwiftSlate's
     * temp clip in place would hand the user's transformed text to every later paste, and
     * IS_SENSITIVE only applies from API 33.
     */
    private fun restoreClipboard(clipboard: ClipboardManager, oldClip: ClipData?, ourText: String) {
        try {
            val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (current != null && current != ourText) return // user copied something newer
            if (oldClip != null) clipboard.setPrimaryClip(oldClip)
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
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
            while (isActive) {
                if (!setFieldText(source, "$baseText ${SPINNER_FRAMES[frameIndex]}")) break
                frameIndex = (frameIndex + 1) % SPINNER_FRAMES.size
                delay(200)
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
        processingStartedAt = 0L
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
