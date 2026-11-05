package com.musheer360.swiftslate.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.SwiftSlateApp
import com.musheer360.swiftslate.api.GeminiClient
import com.musheer360.swiftslate.api.OpenAICompatibleClient
import com.musheer360.swiftslate.bridge.BridgeProtocol
import com.musheer360.swiftslate.bridge.ModuleBridge
import com.musheer360.swiftslate.manager.CommandManager
import com.musheer360.swiftslate.manager.StatsManager
import com.musheer360.swiftslate.model.Command
import com.musheer360.swiftslate.model.CommandType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives "a trigger was typed" from the hooked keyboard (xposed.ImeSession), runs the same
 * command logic the accessibility service used to run, and answers with a *plan* the keyboard
 * applies to the field. API keys, prompts, undo state, stats and localized strings never leave
 * this process.
 */
class HookBridgeService : Service() {

    /** What the keyboard should do. Maps 1:1 onto the BridgeProtocol result keys. */
    private data class Plan(
        val write: String? = null,
        val restore: String? = null,
        val setClip: String? = null,
        val toastOk: String? = null,
        val toastFail: String? = null,
        val haptic: Int = BridgeProtocol.HAPTIC_NONE
    )

    /** State to commit only once the keyboard confirms the write really landed. */
    private data class Commit(
        val trigger: String,
        val fieldKey: String,
        val setUndo: Boolean,
        val undoText: String?,
        val copied: String?,
        val recordUsage: Boolean
    )

    private val client = GeminiClient()
    private val openAIClient = OpenAICompatibleClient()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.w(TAG, "uncaught exception in bridge scope", e)
        }
    )
    private val jobs = ConcurrentHashMap<String, Job>()
    private val pending = ConcurrentHashMap<String, Commit>()
    /** request key -> clean text, for requests whose field currently shows the spinner. */
    private val spinning = ConcurrentHashMap<String, String>()
    private lateinit var commandManager: CommandManager
    private lateinit var statsManager: StatsManager
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        commandManager = CommandManager(applicationContext)
        statsManager = StatsManager(applicationContext)
        messenger = Messenger(Handler(Looper.getMainLooper()) { msg -> handle(msg); true })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------

    private fun handle(msg: Message) {
        val data = msg.data ?: return
        if (!authorized(data)) {
            Log.w(TAG, "rejected bridge message ${msg.what} from uid ${msg.sendingUid}")
            return
        }
        val key = "${msg.sendingUid}:${data.getInt(BridgeProtocol.K_PID)}:${data.getLong(BridgeProtocol.K_ID)}"
        when (msg.what) {
            BridgeProtocol.MSG_HELLO -> recordHeartbeat(data.getString(BridgeProtocol.K_PKG))
            BridgeProtocol.MSG_RUN -> run(msg.replyTo ?: return, data, key)
            BridgeProtocol.MSG_APPLIED -> commit(key, data.getBoolean(BridgeProtocol.K_OK))
            BridgeProtocol.MSG_CANCEL -> {
                jobs.remove(key)?.cancel()
                pending.remove(key)
            }
        }
    }

    private fun authorized(data: Bundle): Boolean {
        val given = data.getString(BridgeProtocol.K_TOKEN) ?: return false
        val expected = ModuleBridge.token(applicationContext)
        return MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())
    }

    private fun recordHeartbeat(pkg: String?) {
        getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putLong(ModuleBridge.PREF_HEARTBEAT_AT, System.currentTimeMillis())
            .putString(ModuleBridge.PREF_HEARTBEAT_PKG, pkg ?: "")
            .apply()
    }

    private fun commit(key: String, ok: Boolean) {
        val c = pending.remove(key) ?: return
        if (!ok) return
        if (c.setUndo) {
            undoText = c.undoText
            undoFieldKey = c.fieldKey
        }
        c.copied?.let { lastCopied = it }
        if (c.recordUsage) statsManager.recordUsage(c.trigger)
    }

    private fun run(replyTo: Messenger, data: Bundle, key: String) {
        val id = data.getLong(BridgeProtocol.K_ID)
        val text = data.getString(BridgeProtocol.K_TEXT) ?: return
        val fieldKey = data.getString(BridgeProtocol.K_FIELD_KEY) ?: ""
        val clip = data.getString(BridgeProtocol.K_CLIP)
        jobs.remove(key)?.cancel()
        jobs[key] = scope.launch {
            val plan = try {
                withTimeout(REQUEST_TIMEOUT_MS) { buildPlan(replyTo, id, key, text, fieldKey, clip) }
            } catch (e: TimeoutCancellationException) {
                Plan(
                    write = spinning[key],
                    toastOk = getString(R.string.toast_request_timed_out),
                    toastFail = getString(R.string.toast_restore_failed) + "\n" +
                        getString(R.string.toast_request_timed_out),
                    haptic = BridgeProtocol.HAPTIC_REJECT
                )
            } catch (e: CancellationException) {
                jobs.remove(key)
                spinning.remove(key)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "command failed", e)
                val msg = getString(ErrorMessages.map(e.message ?: "Unknown error"))
                Plan(
                    write = spinning[key],
                    toastOk = msg,
                    toastFail = getString(R.string.toast_restore_failed) + "\n" + msg,
                    haptic = BridgeProtocol.HAPTIC_REJECT
                )
            }
            jobs.remove(key)
            spinning.remove(key)
            send(replyTo, BridgeProtocol.MSG_RESULT, Bundle().apply {
                putLong(BridgeProtocol.K_ID, id)
                plan.write?.let { putString(BridgeProtocol.K_WRITE, it) }
                plan.restore?.let { putString(BridgeProtocol.K_RESTORE, it) }
                plan.setClip?.let { putString(BridgeProtocol.K_SET_CLIP, it) }
                plan.toastOk?.let { putString(BridgeProtocol.K_TOAST_OK, it) }
                plan.toastFail?.let { putString(BridgeProtocol.K_TOAST_FAIL, it) }
                putInt(BridgeProtocol.K_HAPTIC, plan.haptic)
            })
        }
    }

    private fun send(to: Messenger, what: Int, data: Bundle) {
        try {
            to.send(Message.obtain(null, what).apply { this.data = data })
        } catch (e: Exception) {
            Log.w(TAG, "reply failed (keyboard gone?)", e)
        }
    }

    // ------------------------------------------------------------------------------------
    // The command logic: a port of AssistantService.handleAccessibilityEvent and friends.
    // ------------------------------------------------------------------------------------

    private suspend fun buildPlan(
        replyTo: Messenger, id: Long, key: String,
        text: String, fieldKey: String, clip: String?
    ): Plan {
        val command = commandManager.findCommand(text) ?: return Plan()
        val precedingText = text.substring(0, text.length - command.trigger.length)
        val cleanText = precedingText.trim()
        val trigger = command.trigger

        if (command.isBuiltIn) {
            when {
                trigger.endsWith("undo") -> return undoPlan(key, fieldKey, cleanText, trigger)
                trigger.endsWith("copy") -> return copyPlan(key, fieldKey, precedingText, trigger)
                trigger.endsWith("cut") -> return cutPlan(key, fieldKey, precedingText, trigger)
                trigger.endsWith("paste") ->
                    return pastePlan(key, fieldKey, precedingText, precedingText, clip, trigger)
                trigger.endsWith("replace") ->
                    return pastePlan(key, fieldKey, precedingText, "", clip, trigger)
            }
        }

        return when (command.type) {
            CommandType.TEXT_REPLACER -> {
                pending[key] = Commit(trigger, fieldKey, true, precedingText, null, true)
                Plan(
                    write = precedingText + command.prompt,
                    toastFail = getString(R.string.toast_replace_failed),
                    haptic = BridgeProtocol.HAPTIC_CONFIRM
                )
            }
            CommandType.AI -> aiPlan(replyTo, id, key, fieldKey, cleanText, command)
        }
    }

    private suspend fun aiPlan(
        replyTo: Messenger, id: Long, key: String, fieldKey: String,
        cleanText: String, command: Command
    ): Plan {
        if (cleanText.isEmpty()) return Plan()
        val keyManager = (applicationContext as SwiftSlateApp).keyManager
        if (!keyManager.keystoreAvailable) {
            return Plan(
                toastOk = getString(R.string.keys_keystore_error),
                haptic = BridgeProtocol.HAPTIC_REJECT
            )
        }

        var started = false
        val outcome = runTextCommand(
            applicationContext, keyManager, client, openAIClient, command.prompt, cleanText
        ) {
            started = true
            spinning[key] = cleanText
            send(replyTo, BridgeProtocol.MSG_STARTED, Bundle().apply {
                putLong(BridgeProtocol.K_ID, id)
            })
        }
        // From the first attempt the field holds the spinner instead of the user's text, so
        // every non-success outcome below puts the clean text back first.
        val restoreText = if (started) cleanText else null

        return when (outcome) {
            is CommandOutcome.Success -> {
                pending[key] = Commit(command.trigger, fieldKey, true, cleanText, null, true)
                Plan(
                    write = outcome.text,
                    restore = restoreText,
                    toastFail = getString(R.string.toast_replace_failed),
                    haptic = BridgeProtocol.HAPTIC_CONFIRM
                )
            }
            is CommandOutcome.Refusal -> Plan(
                write = restoreText,
                toastOk = getString(R.string.error_safety_blocked),
                toastFail = getString(R.string.toast_restore_failed) + "\n" +
                    getString(R.string.error_safety_blocked),
                haptic = BridgeProtocol.HAPTIC_REJECT
            )
            is CommandOutcome.Unavailable -> Plan(
                toastOk = outcome.message, haptic = BridgeProtocol.HAPTIC_REJECT
            )
            is CommandOutcome.Failure -> Plan(
                write = restoreText,
                toastOk = outcome.message,
                toastFail = getString(R.string.toast_restore_failed) + "\n" + outcome.message,
                haptic = BridgeProtocol.HAPTIC_REJECT
            )
        }
    }

    private fun undoPlan(key: String, fieldKey: String, currentClean: String, trigger: String): Plan {
        val previous = undoText
        if (previous == null || undoFieldKey != fieldKey) {
            return Plan(
                toastOk = getString(R.string.toast_nothing_to_undo),
                haptic = BridgeProtocol.HAPTIC_REJECT
            )
        }
        // Single-level toggle, exactly like before: undoing again brings the text back.
        pending[key] = Commit(trigger, fieldKey, true, currentClean, null, false)
        return Plan(
            write = previous,
            toastFail = getString(R.string.toast_undo_failed),
            haptic = BridgeProtocol.HAPTIC_CONFIRM
        )
    }

    private fun copyPlan(key: String, fieldKey: String, precedingText: String, trigger: String): Plan {
        val toCopy = precedingText.trim()
        if (toCopy.isEmpty()) {
            return Plan(
                toastOk = getString(R.string.toast_nothing_to_copy),
                haptic = BridgeProtocol.HAPTIC_REJECT
            )
        }
        pending[key] = Commit(trigger, fieldKey, false, null, toCopy, true)
        return Plan(
            write = precedingText, setClip = toCopy,
            toastOk = getString(R.string.toast_copied),
            toastFail = getString(R.string.toast_replace_failed),
            haptic = BridgeProtocol.HAPTIC_CONFIRM
        )
    }

    private fun cutPlan(key: String, fieldKey: String, precedingText: String, trigger: String): Plan {
        val toCut = precedingText.trim()
        if (toCut.isEmpty()) {
            return Plan(
                toastOk = getString(R.string.toast_nothing_to_cut),
                haptic = BridgeProtocol.HAPTIC_REJECT
            )
        }
        pending[key] = Commit(trigger, fieldKey, true, precedingText, toCut, true)
        return Plan(
            write = "", setClip = toCut,
            toastOk = getString(R.string.toast_cut),
            toastFail = getString(R.string.toast_replace_failed),
            haptic = BridgeProtocol.HAPTIC_CONFIRM
        )
    }

    /**
     * `?paste` keeps what precedes the trigger, `?replace` does not. The keyboard reads the
     * clipboard itself (the default IME is allowed to), which is what finally makes pasting
     * text copied in other apps work without any accessibility trick.
     */
    private fun pastePlan(
        key: String, fieldKey: String, precedingText: String, keepPrefix: String,
        clip: String?, trigger: String
    ): Plan {
        val content = if (!clip.isNullOrEmpty()) clip else lastCopied
        if (content.isNullOrEmpty()) {
            return Plan(
                toastOk = getString(R.string.toast_clipboard_empty),
                haptic = BridgeProtocol.HAPTIC_REJECT
            )
        }
        pending[key] = Commit(trigger, fieldKey, true, precedingText, null, true)
        return Plan(
            write = keepPrefix + content,
            toastFail = getString(R.string.toast_replace_failed),
            haptic = BridgeProtocol.HAPTIC_CONFIRM
        )
    }

    private companion object {
        const val TAG = "SwiftSlateBridge"
        const val REQUEST_TIMEOUT_MS = 90_000L

        // Process-lived, like the accessibility service's fields were: the keyboard unbinds
        // when idle, which destroys this Service instance but not the process.
        @Volatile var undoText: String? = null
        @Volatile var undoFieldKey: String? = null
        @Volatile var lastCopied: String? = null
    }
}
