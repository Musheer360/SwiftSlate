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
import com.musheer360.swiftslate.manager.BlocklistManager
import com.musheer360.swiftslate.manager.CommandManager
import com.musheer360.swiftslate.manager.KeyManager
import com.musheer360.swiftslate.manager.StatsManager
import com.musheer360.swiftslate.model.Command
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
    private lateinit var blocklistManager: BlocklistManager
    private lateinit var statsManager: StatsManager
    private val client = GeminiClient()
    private val openAIClient = OpenAICompatibleClient()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    @Volatile
    private var isProcessing = false
    @Volatile
    private var processingStartedAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var triggerLastChars = setOf<Char>()
    private var cachedPrefix = CommandManager.DEFAULT_PREFIX
    private var currentJob: Job? = null
    @Volatile
    private var lastOriginalText: String? = null
    private var lastTriggerRefresh = 0L
    private var currentOverlayToast: View? = null
    private var dismissRunnable: Runnable? = null
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
        val SPINNER_FRAMES_DEFAULT = arrayOf("◐", "◓", "◑", "◒")
        val SPINNER_FRAMES_DOTS = arrayOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
        val SPINNER_FRAMES_SQUARES = arrayOf("▪▫▫", "▫▪▫", "▫▫▪", "▫▪▫")
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
        blocklistManager = BlocklistManager(applicationContext)
        statsManager = StatsManager(applicationContext)
        updateTriggers()
    }

    private fun updateTriggers() {
        cachedPrefix = commandManager.getTriggerPrefix()
        val cmds = commandManager.getCommands()
        triggerLastChars = cmds.mapNotNull { it.trigger.lastOrNull() }.toSet()
        lastTriggerRefresh = System.currentTimeMillis()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return

        if (isProcessing && processingStartedAt > 0L &&
            System.currentTimeMillis() - processingStartedAt > PROCESSING_WATCHDOG_MS) {
            currentJob?.cancel()
        }

        if (isProcessing) return
        val source = event.source ?: return
        val text = source.text?.toString() ?: return
        if (text.isEmpty()) return

        val packageName = event.packageName?.toString() ?: ""
        if (packageName.isNotEmpty() && blocklistManager.isBlocked(packageName)) {
            return
        }

        if (System.currentTimeMillis() - lastTriggerRefresh > TRIGGER_REFRESH_INTERVAL_MS) {
            updateTriggers()
        }

        val lastChar = text[text.length - 1]
        if (!triggerLastChars.contains(lastChar)) {
            if (!lastChar.isLetterOrDigit() || !text.contains("${cachedPrefix}translate:")) {
                return
            }
        }

        val command = commandManager.findCommand(text) ?: return

        val cleanText = text.substring(0, text.length - command.trigger.length).trim()

        if (command.trigger.endsWith("undo") && command.isBuiltIn) {
            if (source.isPassword) { return }
            isProcessing = true
            processingStartedAt = System.currentTimeMillis()
            currentJob?.cancel()
            handleUndo(source, cleanText)
            return
        }

        if (cleanText.isEmpty() || source.isPassword) { return }

        isProcessing = true
        processingStartedAt = System.currentTimeMillis()
        currentJob?.cancel()
        processCommand(source, cleanText, command)
    }

    private fun processCommand(source: AccessibilityNodeInfo, text: String, command: Command) {
        val prefs = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
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
                isProcessing = false
                return
            }
        } else {
            model = prefs.getString("model", "gemini-2.5-flash-lite") ?: "gemini-2.5-flash-lite"
            endpoint = ""
        }
        val temperature = DEFAULT_TEMPERATURE
        val useStructuredOutput = !prefs.getBoolean("structured_output_disabled", false)

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

                        val result = if (providerType == "custom") {
                            openAIClient.generate(command.prompt, text, key, model, temperature, endpoint, useStructuredOutput)
                        } else {
                            client.generate(command.prompt, text, key, model, temperature, useStructuredOutput)
                        }

                        if (result.isSuccess) {
                            spinnerJob?.cancel()
                            spinnerJob = null
                            lastOriginalText = originalText
                            val (generatedText, tokensUsed) = result.getOrThrow()
                            replaceText(source, generatedText)
                            statsManager.recordRequest(command.trigger)
                            statsManager.addTokens(tokensUsed)
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            if (providerType == "custom") {
                                if (openAIClient.structuredOutputFailed) {
                                    prefs.edit().putBoolean("structured_output_disabled", true).apply()
                                }
                            } else {
                                if (client.structuredOutputFailed) {
                                    prefs.edit().putBoolean("structured_output_disabled", true).apply()
                                }
                            }
                            succeeded = true
                            break
                        }

                        val msg = result.exceptionOrNull()?.message ?: ""
                        lastErrorMsg = msg
                        val isRateLimit = msg.contains("Rate limit") || msg.contains("rate limit")
                        val isInvalidKey = msg.contains("Invalid API key", ignoreCase = true) || msg.contains("API key not valid", ignoreCase = true)

                        if (isRateLimit) {
                            val seconds = Regex("retry after (\\d+)s").find(msg)?.groupValues?.get(1)?.toLongOrNull() ?: 60
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
                            showToast("SwiftSlate Error: $lastErrorMsg")
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
                showToast("SwiftSlate Error: ${e.message}")
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    spinnerJob?.cancel()
                    processingStartedAt = 0L
                    if (!handler.postDelayed({ isProcessing = false }, 500)) {
                        isProcessing = false
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
                    replaceText(source, currentText)
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
                    processingStartedAt = 0L
                    if (!handler.postDelayed({ isProcessing = false }, 500)) {
                        isProcessing = false
                    }
                }
            }
        }
    }

    private suspend fun replaceText(source: AccessibilityNodeInfo, newText: String) = withContext(Dispatchers.Main) {
        source.refresh()
        val bundle = Bundle()
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)

        val success = source.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)

        if (!success) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val oldClip = clipboard.primaryClip
            val newClip = ClipData.newPlainText("SwiftSlate Result", newText)
            clipboard.setPrimaryClip(newClip)

            val selectAllArgs = Bundle()
            selectAllArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            selectAllArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, source.text?.length ?: 0)
            source.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs)

            source.performAction(AccessibilityNodeInfo.ACTION_PASTE)

            handler.postDelayed({
                if (oldClip != null) {
                    clipboard.setPrimaryClip(oldClip)
                }
            }, 500)
        }
    }

    private fun setFieldText(source: AccessibilityNodeInfo, text: String) {
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        source.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    private fun startInlineSpinner(source: AccessibilityNodeInfo, baseText: String): Job {
        val prefs = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val style = prefs.getString("spinner_style", "default") ?: "default"

        if (style == "none") {
            return serviceScope.launch(Dispatchers.Main) {
                setFieldText(source, baseText)
                while (isActive) {
                    delay(1000)
                }
            }
        }

        val frames = when (style) {
            "dots" -> SPINNER_FRAMES_DOTS
            "squares" -> SPINNER_FRAMES_SQUARES
            else -> SPINNER_FRAMES_DEFAULT
        }
        
        val delayMs = if (style == "dots") 100L else 200L

        return serviceScope.launch(Dispatchers.Main) {
            var frameIndex = 0
            while (isActive) {
                setFieldText(source, "$baseText ${frames[frameIndex]}")
                frameIndex = (frameIndex + 1) % frames.size
                delay(delayMs)
            }
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
        isProcessing = false
        processingStartedAt = 0L
        currentJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissOverlayToast()
        serviceScope.cancel()
    }
}
