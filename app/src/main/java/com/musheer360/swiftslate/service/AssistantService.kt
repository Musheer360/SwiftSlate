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
                            lastOriginalText = precedingText
                            lastUndoSourceId = sourceId(source)
                            replaceText(source, precedingText + command.prompt)
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            statsManager.recordUsage(command.trigger)
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
            handler.post { Toast.makeText(applicationContext, getString(R.string.toast_keystore_unavailable), Toast.LENGTH_LONG).show() }
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
                    var succeeded = false

                    for (attempt in 0 until maxAttempts) {
                        val key = keyManager.getNextKey()
                        if (key == null) break

                        if (spinnerJob == null) {
                            spinnerJob = startInlineSpinner(source, originalText)
                        }

                        val result = when (provider.transport) {
                            Transport.OPENAI_COMPAT -> openAIClient.generate(
                                command.prompt, text, key, provider.apiModelId(model), temperature, endpoint,
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
                            lastOriginalText = originalText
                            lastUndoSourceId = sourceId(source)
                            val generateResult = result.getOrThrow()
                            replaceText(source, generateResult.text)
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            if (generateResult.structuredOutputFailed) {
                                prefs.edit().putLong(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT, System.currentTimeMillis()).apply()
                            }
                            // A model tuning param (reasoning/thinking) was rejected by the
                            // provider and dropped so the command could still run. This means
                            // the catalog spec is stale/wrong — surface it (throttled to once
                            // per 24h) so the user can report it and we can fix the root cause.
                            if (generateResult.tuningDegraded) {
                                val lastNotified = prefs.getLong(PrefKeys.TUNING_DEGRADED_NOTIFIED_AT, 0L)
                                if (System.currentTimeMillis() - lastNotified > 86_400_000L) {
                                    prefs.edit().putLong(PrefKeys.TUNING_DEGRADED_NOTIFIED_AT, System.currentTimeMillis()).apply()
                                    showToast(getString(R.string.toast_model_setting_rejected))
                                }
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
                                val seconds = apiError.retryAfterSeconds?.toLong() ?: 60
                                keyManager.reportRateLimit(key, seconds)
                            }
                            is ApiError.InvalidKey -> {
                                keyManager.markInvalid(key)
                            }
                            is ApiError.ServerError -> continue // 5xx — try next key
                            else -> break // Non-retryable error, stop trying other keys
                        }
                    }

                    if (!succeeded) {
                        spinnerJob?.cancelAndJoin()
                        spinnerJob = null
                        replaceText(source, originalText)
                        performHapticFeedback(HapticFeedbackConstants.REJECT)
                        if (lastErrorMsg != null) {
                            showToast(mapErrorMessage(lastErrorMsg))
                        } else {
                            val waitMs = keyManager.getShortestWaitTimeMs()
                            if (waitMs != null) {
                                val waitSec = ((waitMs + 999) / 1000).coerceAtLeast(1)
                                showToast(getString(R.string.toast_key_rate_limited, waitSec))
                            } else if (keyManager.getKeys().isEmpty()) {
                                showToast(getString(R.string.toast_no_keys))
                            } else {
                                showToast(getString(R.string.toast_all_keys_invalid))
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                spinnerJob?.cancelAndJoin()
                try { replaceText(source, originalText) } catch (_: Exception) {}
                showToast(getString(R.string.toast_request_timed_out))
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    spinnerJob?.cancel()
                    try { replaceText(source, originalText) } catch (_: Exception) {}
                }
                throw e
            } catch (e: Exception) {
                spinnerJob?.cancelAndJoin()
                try { replaceText(source, originalText) } catch (_: Exception) {
                    showToast(getString(R.string.toast_restore_failed))
                }
                showToast(mapErrorMessage(e.message ?: "Unknown error"))
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
                } else {
                    lastOriginalText = currentText
                    replaceText(source, previousText)
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
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
                            lastCopiedText = textToCopy
                            withContext(Dispatchers.Main) {
                                clipboard.setPrimaryClip(ClipData.newPlainText("SwiftSlate", textToCopy))
                                replaceText(source, precedingText)
                            }
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            showToast(getString(R.string.toast_copied))
                            statsManager.recordUsage(command.trigger)
                        }
                    }
                    trigger.endsWith("cut") -> {
                        val textToCut = precedingText.trim()
                        if (textToCut.isEmpty()) {
                            performHapticFeedback(HapticFeedbackConstants.REJECT)
                            showToast(getString(R.string.toast_nothing_to_cut))
                        } else {
                            lastCopiedText = textToCut
                            lastOriginalText = precedingText
                            lastUndoSourceId = sourceId(source)
                            withContext(Dispatchers.Main) {
                                clipboard.setPrimaryClip(ClipData.newPlainText("SwiftSlate", textToCut))
                                replaceText(source, "")
                            }
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            showToast(getString(R.string.toast_cut))
                            statsManager.recordUsage(command.trigger)
                        }
                    }
                    trigger.endsWith("paste") -> {
                        val pasteText = lastCopiedText ?: clipText
                        if (pasteText.isNullOrEmpty()) {
                            performHapticFeedback(HapticFeedbackConstants.REJECT)
                            showToast(getString(R.string.toast_clipboard_empty))
                        } else {
                            lastOriginalText = precedingText
                            lastUndoSourceId = sourceId(source)
                            withContext(Dispatchers.Main) {
                                replaceText(source, precedingText + pasteText)
                            }
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            statsManager.recordUsage(command.trigger)
                        }
                    }
                    trigger.endsWith("replace") -> {
                        val pasteText = lastCopiedText ?: clipText
                        if (pasteText.isNullOrEmpty()) {
                            performHapticFeedback(HapticFeedbackConstants.REJECT)
                            showToast(getString(R.string.toast_clipboard_empty))
                        } else {
                            lastOriginalText = precedingText
                            lastUndoSourceId = sourceId(source)
                            withContext(Dispatchers.Main) {
                                replaceText(source, pasteText)
                            }
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            statsManager.recordUsage(command.trigger)
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

    private suspend fun replaceText(source: AccessibilityNodeInfo, newText: String) = withContext(Dispatchers.Main) {
        if (!source.refresh()) return@withContext
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
                return@withContext // Text persisted
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
        if (source.text == null) return@withContext
        val selectAllArgs = Bundle()
        selectAllArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
        selectAllArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, source.text?.length ?: 0)
        source.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs)

        source.performAction(AccessibilityNodeInfo.ACTION_PASTE)

        scheduleTextVerification(source, newText)

        handler.postDelayed({
            try {
                source.refresh()
                val fieldText = source.text?.toString()
                // Restore original clipboard regardless of paste success.
                // If paste succeeded, fieldText == newText and clipboard holds our temp data.
                // If paste failed, clipboard still holds our temp data that should be cleaned.
                val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (current == newText) {
                    if (oldClip != null) {
                        clipboard.setPrimaryClip(oldClip)
                    } else {
                        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                }
            } catch (_: Exception) {}
        }, 500)
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

    private fun mapErrorMessage(raw: String): String = ErrorMessages.map(this, raw)

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
