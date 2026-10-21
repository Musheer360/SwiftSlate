package com.musheer360.swiftslate.bridge

import android.app.Application
import android.content.Context
import android.util.Log
import com.musheer360.swiftslate.manager.CommandManager
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.Executors

/** Whether SwiftSlate is actually working as an LSPosed module right now. */
enum class ModuleState {
    /** A hooked keyboard has reported in since this build was installed. */
    ACTIVE,
    /** LSPosed knows the module, but no keyboard has loaded it yet (scope / restart needed). */
    AWAITING_KEYBOARD,
    /** LSPosed is not connected to this app: not installed, or the module is not enabled. */
    INACTIVE
}

/**
 * App-side half of the LSPosed integration: tracks the LSPosed service binding, publishes the
 * trigger snapshot the keyboard hook reads, and reports module state to the UI.
 */
object ModuleBridge {
    private const val TAG = "SwiftSlateModule"
    private const val SETTINGS = "settings"
    private const val PREF_TOKEN = "bridge_token"
    const val PREF_HEARTBEAT_AT = "hook_heartbeat_at"
    const val PREF_HEARTBEAT_PKG = "hook_heartbeat_pkg"

    @Volatile private var service: XposedService? = null
    @Volatile private var appContext: Context? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "swiftslate-snapshot").apply { isDaemon = true }
    }

    fun init(app: Application) {
        appContext = app.applicationContext
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    this@ModuleBridge.service = service
                    publishSnapshot()
                }

                override fun onServiceDied(service: XposedService) {
                    this@ModuleBridge.service = null
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "XposedServiceHelper.registerListener failed", t)
        }
    }

    /**
     * Shared secret between this app and the hooked keyboards. It travels only through LSPosed
     * remote preferences, which the framework hands to processes in the module's scope, so an
     * arbitrary installed app that merely binds the bridge cannot spend the user's API keys.
     */
    @Synchronized
    fun token(context: Context): String {
        val prefs = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        prefs.getString(PREF_TOKEN, null)?.takeIf { it.length >= 32 }?.let { return it }
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val fresh = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(PREF_TOKEN, fresh).apply()
        return fresh
    }

    /** Safe to call from any thread, any number of times; it coalesces onto one worker. */
    fun publishSnapshot() {
        val ctx = appContext ?: return
        executor.execute {
            val svc = service ?: return@execute
            try {
                val json = buildSnapshot(ctx)
                svc.getRemotePreferences(BridgeProtocol.PREFS_SNAPSHOT)
                    .edit()
                    .putString(BridgeProtocol.PREF_SNAPSHOT_JSON, json)
                    .apply()
            } catch (t: Throwable) {
                Log.w(TAG, "publishing trigger snapshot failed", t)
            }
        }
    }

    internal fun buildSnapshot(context: Context): String {
        val manager = CommandManager(context)
        val arr = JSONArray()
        for (cmd in manager.getCommands()) {          // already sorted longest trigger first
            if (cmd.trigger.endsWith("translate:xx")) continue
            val needsClip = cmd.isBuiltIn &&
                (cmd.trigger.endsWith("paste") || cmd.trigger.endsWith("replace"))
            arr.put(JSONObject().put("t", cmd.trigger).put("c", if (needsClip) 1 else 0))
        }
        return JSONObject()
            .put("v", 1)
            .put("token", token(context))
            .put("prefix", manager.getTriggerPrefix())
            .put("triggers", arr)
            .toString()
    }

    fun state(context: Context): ModuleState {
        val prefs = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        val heartbeat = try { prefs.getLong(PREF_HEARTBEAT_AT, 0L) } catch (_: Exception) { 0L }
        val installedAt = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (_: Exception) { 0L }
        return when {
            heartbeat > installedAt -> ModuleState.ACTIVE
            service != null -> ModuleState.AWAITING_KEYBOARD
            else -> ModuleState.INACTIVE
        }
    }
}
