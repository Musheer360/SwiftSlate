package com.musheer360.swiftslate.service

import android.accessibilityservice.AccessibilityService
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
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
import com.musheer360.swiftslate.model.ProviderType
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
    private var lastReplacedText: String? = null
    @Volatile
    private var lastReplacedAt = 0L
    @Volatile
    private var lastReplacedSource: AccessibilityNodeInfo? = null
    private var verifyRunnable: Runnable? = null
    private var lastTriggerRefresh = 0L
    private var currentOverlayToast: View? = null
    private var dismissRunnable: Runnable? = null
    private var watchdogRunnable: Runnable? = null
    private var dismissAnimator: AnimatorSet? = null
    private var enterAnimator: AnimatorSet? = null

    private fun dp(value: Int): Int {
        val density = resources.displayMetrics.density
        return (value * density + 0.5f).toInt()
    }

    private fun sourceId(source: AccessibilityNodeInfo): String =
        "${source.windowId}:${source.viewIdResourceName ?: source.hashCode()}"

    private companion object {
        const val TRIGGER_REFRESH_INTERVAL_MS = 5_000L
        const val DEFAULT_TEMPERATURE = 0.5
        const val PROCESSING_WATCHDOG_MS = 120_000L
        val SPINNER_FRAMES = arrayOf("◐", "◓", "◑", "◒")
        const val TOAST_BACKGROUND_COLOR = 0xE6323232.toInt()
        const val TOAST_DURATION_MS = 3500L
        const val TOAST_BOTTOM_MARGIN_DP = 64
        const val TOAST_ANIM_DURATION_MS = 300L
        const val TOAST_SLIDE_DISTANCE_DP = 40
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
            source.recycle()
            return
        }
        val text = source.text?.toString() ?: run {
            source.recycle()
            return
        }
        if (text.isEmpty()) {
            verifyRunnable?.let { handler.removeCallbacks(it) }
            lastReplacedText = null
            val prev = lastReplacedSource
            lastReplacedSource = null
            if (prev != null && prev !== source) {
                try { prev.recycle() } catch (_: Exception) {}
            }
            source.recycle()
            return
        }

        // Skip events where text matches what we just replaced (prevents IME re-commit race)
        val replaced = lastReplacedText
        if (replaced != null && text == replaced &&
            System.currentTimeMillis() - lastReplacedAt < 1000) {
            source.recycle()
            return
        }

        if (System.currentTimeMillis() - lastTriggerRefresh > TRIGGER_REFRESH_INTERVAL_MS) {
            updateTriggers()
        }

        val lastChar = text[text.length - 1]
        if (!triggerLastChars.contains(lastChar)) {
            if (!lastChar.isLetterOrDigit() || !text.contains(cachedTranslatePrefix)) {
                source.recycle()
                return
            }
        }

        val command = commandManager.findCommand(text) ?: run {
            source.recycle()
            return
        }

        val precedingText = text.substring(0, text.length - command.trigger.length)
        val cleanText = precedingText.trim()

        if (command.trigger.endsWith("undo") && command.isBuiltIn) {
            if (!isProcessing.compareAndSet(false, true)) {
                source.recycle()
                return
            }
            processingStartedAt = System.currentTimeMillis()
            startWatchdog()
            cancelPendingProcessingReset()
            currentJob?.cancel()
            handleUndo(source, cleanText)
            return
        }

        when (command.type) {
            CommandType.TEXT_REPLACER -> {
                if (!isProcessing.compareAndSet(false, true)) {
                    source.recycle()
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
                            showToast("Could not replace text")
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
                    source.recycle()
                    return
                }
                if (!isProcessing.compareAndSet(false, true)) {
                    source.recycle()
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
            handler.post { Toast.makeText(applicationContext, "Secure key storage unavailable. Please reinstall the app.", Toast.LENGTH_LONG).show() }
            cancelWatchdog()
            processingStartedAt = 0L
            isProcessing.set(false)
            recycleIfUnowned(source)
            return
        }

        currentJob = serviceScope.launch {
            val thisJob = coroutineContext[Job]
            val prefs = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val providerType = ProviderType.sanitize(prefs.getString("provider_type", null))
            val model: String
            val endpoint: String

            if (providerType == ProviderType.CUSTOM) {
                model = prefs.getString("custom_model", "") ?: ""
                endpoint = prefs.getString("custom_endpoint", "") ?: ""
                if (model.isBlank() || endpoint.isBlank()) {
                    showToast("Custom provider not configured. Set endpoint and model in Settings.")
                    withContext(NonCancellable + Dispatchers.Main) {
                        cancelWatchdog()
                        processingStartedAt = 0L
                        scheduleProcessingReset()
                        recycleIfUnowned(source)
                    }
                    return@launch
                }
            } else if (providerType == ProviderType.GROQ) {
                model = prefs.getString("groq_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"
                endpoint = "https://api.groq.com/openai/v1"
            } else {
                model = prefs.getString("model", "gemini-2.5-flash-lite") ?: "gemini-2.5-flash-lite"
                endpoint = ""
            }
            val temperature = prefs.getFloat("temperature", DEFAULT_TEMPERATURE.toFloat()).toDouble()
            val useStructuredOutput = run {
                val disabledAt = prefs.getLong("structured_output_disabled_at", 0L)
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

                        val isGroq = providerType == ProviderType.GROQ
                        val result = if (isGroq || providerType == ProviderType.CUSTOM) {
                            openAIClient.generate(command.prompt, text, key, model, temperature, endpoint,
                                useStructuredOutput = false,
                                useJsonObjectMode = isGroq && useStructuredOutput)
                        } else {
                            client.generate(command.prompt, text, key, model, temperature, useStructuredOutput)
                        }

                        if (result.isSuccess) {
                            spinnerJob?.cancelAndJoin()
                            spinnerJob = null
                            lastOriginalText = originalText
                            lastUndoSourceId = sourceId(source)
                            val generateResult = result.getOrThrow()
                            replaceText(source, generateResult.text)
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            if (generateResult.structuredOutputFailed) {
                                prefs.edit().putLong("structured_output_disabled_at", System.currentTimeMillis()).apply()
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
                                showToast("API key rate limited. Try again in ${waitSec}s")
                            } else if (keyManager.getKeys().isEmpty()) {
                                showToast("No API keys configured")
                            } else {
                                showToast("All API keys are invalid. Please check your keys")
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                spinnerJob?.cancelAndJoin()
                try { replaceText(source, originalText) } catch (_: Exception) {}
                showToast("Request timed out")
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    spinnerJob?.cancel()
                    try { replaceText(source, originalText) } catch (_: Exception) {}
                }
                throw e
            } catch (e: Exception) {
                spinnerJob?.cancelAndJoin()
                try { replaceText(source, originalText) } catch (_: Exception) {
                    showToast("Could not restore original text")
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
                    showToast("Nothing to undo")
                } else {
                    lastOriginalText = currentText
                    replaceText(source, previousText)
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("Could not undo")
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
                if (fieldText == newText) {
                    val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                    if (current == newText) {
                        if (oldClip != null) {
                            clipboard.setPrimaryClip(oldClip)
                        } else {
                            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                        }
                    }
                }
            } catch (_: Exception) {}
        }, 500)
    }

    /** Recycle source only if scheduleTextVerification didn't take ownership. */
    private fun recycleIfUnowned(source: AccessibilityNodeInfo) {
        if (lastReplacedSource !== source) {
            try { source.recycle() } catch (_: Exception) {}
        }
    }

    private fun scheduleTextVerification(source: AccessibilityNodeInfo, expectedText: String) {
        lastReplacedText = expectedText
        lastReplacedAt = System.currentTimeMillis()
        // Recycle the previous source if it's a different node
        val prev = lastReplacedSource
        if (prev != null && prev !== source) {
            try { prev.recycle() } catch (_: Exception) {}
        }
        lastReplacedSource = source
        verifyRunnable?.let { handler.removeCallbacks(it) }
        val capturedSource = source
        val runnable = Runnable {
            try {
                if (!capturedSource.refresh()) return@Runnable
                val currentText = capturedSource.text?.toString()
                val isImeClobber = currentText != null && currentText.isNotEmpty() && expectedText.startsWith(currentText)
                if (isImeClobber && currentText != expectedText && currentText!!.length < expectedText.length) {
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
                    try { capturedSource.recycle() } catch (_: Exception) {}
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

    private fun mapErrorMessage(raw: String): String {
        val lower = raw.lowercase()
        return when {
            lower.contains("permission_denied") || lower.contains("permission denied") ->
                "Your API key doesn't have access to this model."
            lower.contains("invalid api key") || lower.contains("api key not valid") || lower.contains("api_key_invalid") ->
                "Invalid API key. Please check your key in Settings."
            lower.contains("rate limit") || lower.contains("resource_exhausted") || lower.contains("quota") ->
                "Rate limited. Try again shortly."
            lower.contains("model not found") || lower.contains("model_not_found") || lower.contains("not found for api version") ->
                "Model not found. Check your model selection in Settings."
            lower.contains("safety") || lower.contains("content_filter") || lower.contains("recitation") ||
                lower.contains("blocked by safety") || lower.contains("finish_reason: safety") ->
                "Response blocked by safety filters. Try rephrasing."
            lower.contains("empty response") || lower.contains("no content found") || lower.contains("no choices found") ->
                "Model returned an empty response. Try again."
            lower.contains("timeout") || lower.contains("timed out") ->
                "Request timed out. Check your connection."
            lower.contains("unable to resolve host") || lower.contains("no address associated") ||
                lower.contains("network is unreachable") || lower.contains("no route to host") ->
                "No internet connection."
            lower.contains("connection refused") || lower.contains("connect failed") ->
                "Could not reach the API. Check your endpoint URL."
            lower.contains("bad request") ->
                "Request failed. Check your settings."
            else -> raw
        }
    }

    private suspend fun showToast(msg: String) = withContext(Dispatchers.Main) {
        dismissOverlayToast()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val textView = TextView(applicationContext).apply {
            text = msg
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(24), dp(12), dp(24), dp(12))
            maxWidth = (resources.displayMetrics.widthPixels * 0.85).toInt()
            background = GradientDrawable().apply {
                setColor(TOAST_BACKGROUND_COLOR)
                cornerRadius = dp(24).toFloat()
            }
            gravity = Gravity.CENTER
            alpha = 0f
            translationY = dp(TOAST_SLIDE_DISTANCE_DP).toFloat()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(TOAST_BOTTOM_MARGIN_DP)
            windowAnimations = 0
        }

        try {
            wm.addView(textView, params)
            currentOverlayToast = textView

            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(textView, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(textView, View.TRANSLATION_Y, dp(TOAST_SLIDE_DISTANCE_DP).toFloat(), 0f)
                )
                duration = TOAST_ANIM_DURATION_MS
                interpolator = DecelerateInterpolator()
                start()
                enterAnimator = this
            }

            val runnable = Runnable { dismissOverlayToastAnimated() }
            dismissRunnable = runnable
            handler.postDelayed(runnable, TOAST_DURATION_MS)
        } catch (_: Exception) {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dismissOverlayToast() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        enterAnimator?.cancel()
        enterAnimator = null
        dismissAnimator?.cancel()
        dismissAnimator = null
        currentOverlayToast?.let { view ->
            try {
                view.visibility = View.GONE
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(view)
            } catch (_: Exception) {}
            currentOverlayToast = null
        }
    }

    private fun dismissOverlayToastAnimated() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        enterAnimator?.cancel()
        enterAnimator = null
        dismissAnimator?.cancel()
        currentOverlayToast?.let { view ->
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                dismissAnimator = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(view, View.ALPHA, view.alpha, 0f),
                        ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, view.translationY, dp(TOAST_SLIDE_DISTANCE_DP).toFloat())
                    )
                    duration = TOAST_ANIM_DURATION_MS
                    interpolator = DecelerateInterpolator()
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            view.visibility = View.GONE
                            try { wm.removeView(view) } catch (_: Exception) {}
                            dismissAnimator = null
                        }
                    })
                    start()
                }
            } catch (_: Exception) {}
            currentOverlayToast = null
        }
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
        try { lastReplacedSource?.recycle() } catch (_: Exception) {}
        lastReplacedSource = null
        dismissOverlayToast()
    }

    override fun onDestroy() {
        super.onDestroy()
        isProcessing.set(false)
        lastReplacedText = null
        lastReplacedAt = 0L
        try { lastReplacedSource?.recycle() } catch (_: Exception) {}
        lastReplacedSource = null
        handler.removeCallbacksAndMessages(null)
        dismissOverlayToast()
        serviceScope.cancel()
    }
}
