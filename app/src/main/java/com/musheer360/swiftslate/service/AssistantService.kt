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
import com.musheer360.swiftslate.api.GeminiClient
import com.musheer360.swiftslate.api.OpenAICompatibleClient
import com.musheer360.swiftslate.manager.CommandManager
import com.musheer360.swiftslate.manager.KeyManager
import com.musheer360.swiftslate.model.Command
import com.musheer360.swiftslate.model.CommandType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AssistantService : AccessibilityService() {

    private lateinit var keyManager: KeyManager
    private lateinit var commandManager: CommandManager
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
    private var currentJob: Job? = null
    @Volatile
    private var lastOriginalText: String? = null
    @Volatile
    private var lastReplacedText: String? = null
    @Volatile
    private var lastReplacedAt = 0L
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

    private companion object {
        const val TRIGGER_REFRESH_INTERVAL_MS = 5_000L
        const val DEFAULT_TEMPERATURE = 0.5
        const val PROCESSING_WATCHDOG_MS = 120_000L
        val SPINNER_FRAMES = arrayOf("◐", "◓", "◑", "◒")
        private val RETRY_AFTER_REGEX = Regex("retry after (\\d+)s")
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        if (event.packageName?.toString() == packageName) return

        if (isProcessing.get()) return
        val source = event.source ?: return
        if (source.isPassword) return
        val text = source.text?.toString() ?: return
        if (text.isEmpty()) {
            verifyRunnable?.let { handler.removeCallbacks(it) }
            lastReplacedText = null
            lastReplacedSource = null
            return
        }

        // Skip events where text matches what we just replaced (prevents IME re-commit race)
        val replaced = lastReplacedText
        if (replaced != null && text == replaced &&
            System.currentTimeMillis() - lastReplacedAt < 1000) return

        if (System.currentTimeMillis() - lastTriggerRefresh > TRIGGER_REFRESH_INTERVAL_MS) {
            updateTriggers()
        }

        val lastChar = text[text.length - 1]
        if (!triggerLastChars.contains(lastChar)) {
            if (!lastChar.isLetterOrDigit() || !text.contains(cachedTranslatePrefix)) {
                return
            }
        }

        val command = commandManager.findCommand(text) ?: return

        val precedingText = text.substring(0, text.length - command.trigger.length)
        val cleanText = precedingText.trim()

        if (command.trigger.endsWith("undo") && command.isBuiltIn) {
            if (!isProcessing.compareAndSet(false, true)) return
            processingStartedAt = System.currentTimeMillis()
            startWatchdog()
            currentJob?.cancel()
            handleUndo(source, cleanText)
            return
        }

        when (command.type) {
            CommandType.TEXT_REPLACER -> {
                if (!isProcessing.compareAndSet(false, true)) return
                processingStartedAt = System.currentTimeMillis()
                startWatchdog()
                currentJob?.cancel()
                currentJob = serviceScope.launch {
                    try {
                        withContext(Dispatchers.Main) {
                            lastOriginalText = precedingText.ifEmpty { text }
                            replaceText(source, precedingText + command.prompt)
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            showToast("Could not replace text")
                        }
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main) {
                            cancelWatchdog()
                            processingStartedAt = 0L
                            if (!handler.postDelayed({ isProcessing.set(false) }, 500)) {
                                isProcessing.set(false)
                            }
                        }
                    }
                }
            }
            CommandType.AI -> {
                if (cleanText.isEmpty()) return
                if (!isProcessing.compareAndSet(false, true)) return
                processingStartedAt = System.currentTimeMillis()
                startWatchdog()
                currentJob?.cancel()
                processCommand(source, cleanText, command)
            }
        }
    }

    private fun processCommand(source: AccessibilityNodeInfo, text: String, command: Command) {
        val prefs = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

        if (!keyManager.keystoreAvailable) {
            serviceScope.launch { showToast("Secure key storage unavailable. Please reinstall the app.") }
            cancelWatchdog()
            processingStartedAt = 0L
            isProcessing.set(false)
            return
        }

        val providerType = prefs.getString("provider_type", "gemini") ?: "gemini"
        val model: String
        val endpoint: String

        if (providerType == "custom") {
            model = prefs.getString("custom_model", "") ?: ""
            endpoint = prefs.getString("custom_endpoint", "") ?: ""
            if (model.isBlank() || endpoint.isBlank()) {
                serviceScope.launch {
                    showToast("Custom provider not configured. Set endpoint and model in Settings.")
                }
                cancelWatchdog()
                processingStartedAt = 0L
                isProcessing.set(false)
                return
            }
        } else if (providerType == "groq") {
            model = prefs.getString("groq_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"
            endpoint = "https://api.groq.com/openai/v1"
        } else {
            model = prefs.getString("model", "gemini-2.5-flash-lite") ?: "gemini-2.5-flash-lite"
            endpoint = ""
        }
        val temperature = DEFAULT_TEMPERATURE
        val useStructuredOutput = run {
            val disabledAt = prefs.getLong("structured_output_disabled_at", 0L)
            System.currentTimeMillis() - disabledAt > 86_400_000L // re-try after 24h
        }

        currentJob = serviceScope.launch {
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

                        val isGroq = providerType == "groq"
                        val result = if (isGroq || providerType == "custom") {
                            openAIClient.generate(command.prompt, text, key, model, temperature, endpoint,
                                useStructuredOutput = false,
                                useJsonObjectMode = isGroq && useStructuredOutput)
                        } else {
                            client.generate(command.prompt, text, key, model, temperature, useStructuredOutput)
                        }

                        if (result.isSuccess) {
                            spinnerJob.cancel()
                            spinnerJob = null
                            lastOriginalText = originalText
                            replaceText(source, result.getOrThrow())
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            if ((providerType == "gemini" && client.structuredOutputFailed) ||
                                (providerType in setOf("custom", "groq") && openAIClient.structuredOutputFailed)) {
                                prefs.edit().putLong("structured_output_disabled_at", System.currentTimeMillis()).apply()
                            }
                            succeeded = true
                            break
                        }

                        val msg = result.exceptionOrNull()?.message ?: ""
                        lastErrorMsg = msg
                        val isRateLimit = msg.contains("Rate limit") || msg.contains("rate limit")
                        val isInvalidKey = msg.contains("Invalid API key", ignoreCase = true) || msg.contains("API key not valid", ignoreCase = true)

                        if (isRateLimit) {
                            val seconds = RETRY_AFTER_REGEX.find(msg)?.groupValues?.get(1)?.toLongOrNull() ?: 60
                            keyManager.reportRateLimit(key, seconds)
                        } else if (isInvalidKey) {
                            keyManager.markInvalid(key)
                        } else {
                            break // Non-retryable error, stop trying other keys
                        }
                    }

                    if (!succeeded) {
                        spinnerJob?.cancel()
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
                spinnerJob?.cancel()
                try { replaceText(source, originalText) } catch (_: Exception) {}
                showToast("Request timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                spinnerJob?.cancel()
                try { replaceText(source, originalText) } catch (_: Exception) {
                    showToast("Could not restore original text")
                }
                showToast(mapErrorMessage(e.message ?: "Unknown error"))
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    cancelWatchdog()
                    spinnerJob?.cancel()
                    processingStartedAt = 0L
                    if (!handler.postDelayed({ isProcessing.set(false) }, 500)) {
                        isProcessing.set(false)
                    }
                }
            }
        }
    }

    private fun handleUndo(source: AccessibilityNodeInfo, currentText: String) {
        currentJob = serviceScope.launch {
            try {
                val previousText = lastOriginalText
                if (previousText == null) {
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
                    cancelWatchdog()
                    processingStartedAt = 0L
                    if (!handler.postDelayed({ isProcessing.set(false) }, 500)) {
                        isProcessing.set(false)
                    }
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
            val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (current == newText) {
                if (oldClip != null) {
                    clipboard.setPrimaryClip(oldClip)
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }, 500)
    }

    private fun scheduleTextVerification(source: AccessibilityNodeInfo, expectedText: String) {
        lastReplacedText = expectedText
        lastReplacedAt = System.currentTimeMillis()
        lastReplacedSource = source
        verifyRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            try {
                if (!source.refresh()) return@Runnable
                val currentText = source.text?.toString()
                if (currentText != null && currentText.isNotEmpty() && currentText != expectedText && currentText.length < expectedText.length && expectedText.startsWith(currentText)) {
                    // IME clobbered our text — re-set it
                    val bundle = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expectedText)
                    }
                    source.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                }
            } catch (_: Exception) {
            } finally {
                lastReplacedText = null
                lastReplacedSource = null
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
        handler.removeCallbacksAndMessages(null)
        lastReplacedText = null
        lastReplacedAt = 0L
        lastReplacedSource = null
        dismissOverlayToast()
    }

    override fun onDestroy() {
        super.onDestroy()
        isProcessing.set(false)
        lastReplacedText = null
        lastReplacedAt = 0L
        lastReplacedSource = null
        handler.removeCallbacksAndMessages(null)
        dismissOverlayToast()
        serviceScope.cancel()
    }
}
