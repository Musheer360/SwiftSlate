package com.musheer360.swiftslate.manager

import android.content.Context
import android.content.SharedPreferences
import com.musheer360.swiftslate.model.Command
import com.musheer360.swiftslate.model.CommandType
import org.json.JSONArray
import org.json.JSONObject

class CommandManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("commands", Context.MODE_PRIVATE)
    private val settingsPrefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Volatile
    private var cachedCommands: List<Command>? = null
    @Volatile
    private var cacheTimestamp = 0L

    companion object {
        const val DEFAULT_PREFIX = "?"
        const val PREF_TRIGGER_PREFIX = "trigger_prefix"
        private const val CACHE_TTL_MS = 5_000L
    }

    // Built-in command names (without prefix) and their prompts
    private val builtInDefinitions = listOf(
        "fix" to "Fix grammar, spelling, and punctuation errors.",
        "improve" to "Rewrite to improve clarity, flow, and coherence.",
        "shorten" to "Rewrite to be more concise while preserving the core meaning.",
        "expand" to "Rewrite with more detail. Elaborate only on what is stated or widely known \u2014 do not fabricate information.",
        "formal" to "Rewrite in a formal, professional tone.",
        "casual" to "Rewrite in a casual, friendly tone.",
        "emoji" to "Add relevant emojis throughout.",
        "human" to "Rewrite to sound naturally human, not AI-generated. Never use emdashes or semicolons, use commas or periods instead. Drop AI clichés and filler phrases. Use contractions, everyday words, and varied sentence lengths. Keep all facts, names, and numbers intact.",
        "reply" to "Generate a contextual reply to this message.",
        "undo" to "Undo the last replacement and restore the original text.",
        "copy" to "Copy the text to clipboard.",
        "cut" to "Cut the text to clipboard.",
        "paste" to "Paste from clipboard.",
        "replace" to "Replace text with clipboard content."
    )

    fun getTriggerPrefix(): String {
        return settingsPrefs.getString(PREF_TRIGGER_PREFIX, DEFAULT_PREFIX) ?: DEFAULT_PREFIX
    }

    @Synchronized fun setTriggerPrefix(newPrefix: String): Boolean {
        if (newPrefix.length != 1 || newPrefix[0].isLetterOrDigit() || newPrefix[0].isWhitespace()) return false
        // Write prefix first so crash between writes is self-healing on retry
        settingsPrefs.edit().putString(PREF_TRIGGER_PREFIX, newPrefix).apply()
        // Migrate custom command triggers — idempotent: always fix commands not matching current prefix
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = try { JSONArray(customStr) } catch (_: Exception) {
            prefs.edit().putString("custom_commands", "[]").apply()
            cachedCommands = null
            return true
        }
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val oldTrigger = obj.getString("trigger")
            val migrated = if (!oldTrigger.startsWith(newPrefix)) {
                // Strip any single-char non-alphanumeric prefix, then apply new prefix
                val stripped = if (oldTrigger.isNotEmpty() && !oldTrigger[0].isLetterOrDigit()) oldTrigger.substring(1) else oldTrigger
                newPrefix + stripped
            } else oldTrigger
            val newObj = JSONObject()
            newObj.put("trigger", migrated)
            newObj.put("prompt", obj.getString("prompt"))
            newObj.put("type", obj.optString("type", CommandType.AI.name))
            newArr.put(newObj)
        }
        prefs.edit().putString("custom_commands", newArr.toString()).apply()
        cachedCommands = null
        return true
    }

    private fun getBuiltInCommands(): List<Command> {
        val prefix = getTriggerPrefix()
        return builtInDefinitions.map { (name, prompt) -> Command("$prefix$name", prompt, true) }
    }

    @Volatile
    private var migrating = false

    @Synchronized fun getCommands(): List<Command> {
        val now = System.currentTimeMillis()
        val cached = cachedCommands
        if (cached != null && now - cacheTimestamp < CACHE_TTL_MS) return cached
        val prefix = getTriggerPrefix()
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = JSONArray(customStr)
        val customCommands = mutableListOf<Command>()
        var needsMigration = false
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val trigger = obj.getString("trigger")
            if (!trigger.startsWith(prefix)) needsMigration = true
            customCommands.add(Command(trigger, obj.getString("prompt"), false,
                try { CommandType.valueOf(obj.optString("type", CommandType.AI.name)) } catch (_: Exception) { CommandType.AI }))
        }
        // Self-heal prefix mismatch (e.g. crash between two apply() calls in setTriggerPrefix)
        if (needsMigration && !migrating) {
            migrating = true
            try {
                setTriggerPrefix(prefix)
                return getCommands()
            } finally {
                migrating = false
            }
        }
        val result = (getBuiltInCommands() + customCommands).sortedByDescending { it.trigger.length }
        cachedCommands = result
        cacheTimestamp = System.currentTimeMillis()
        return result
    }

    @Synchronized fun addCustomCommand(command: Command) {
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = JSONArray(customStr)
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("trigger") != command.trigger) {
                newArr.put(obj)
            }
        }
        val newObj = JSONObject()
        newObj.put("trigger", command.trigger)
        newObj.put("prompt", command.prompt)
        newObj.put("type", command.type.name)
        newArr.put(newObj)
        prefs.edit().putString("custom_commands", newArr.toString()).apply()
        cachedCommands = null
    }

    @Synchronized fun removeCustomCommand(trigger: String) {
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = JSONArray(customStr)
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("trigger") != trigger) {
                newArr.put(obj)
            }
        }
        prefs.edit().putString("custom_commands", newArr.toString()).apply()
        cachedCommands = null
    }

    @Synchronized fun exportCommands(): String {
        return prefs.getString("custom_commands", "[]") ?: "[]"
    }

    @Synchronized fun importCommands(json: String): Boolean {
        return try {
            val arr = JSONArray(json)
            if (arr.length() > 100) return false
            val prefix = getTriggerPrefix()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val trigger = obj.optString("trigger", "")
                val prompt = obj.optString("prompt", "")
                if (trigger.isBlank() || prompt.isBlank()) return false
                if (trigger.length > 50 || prompt.length > 5000) return false
                if (!trigger.startsWith(prefix)) return false
                val type = obj.optString("type", CommandType.AI.name)
                if (type != CommandType.AI.name && type != CommandType.TEXT_REPLACER.name) return false
            }
            prefs.edit().putString("custom_commands", arr.toString()).apply()
            cachedCommands = null
            true
        } catch (_: Exception) {
            false
        }
    }

    fun findCommand(text: String): Command? {
        val commands = getCommands()
        for (cmd in commands) {  // Already sorted by trigger length in getCommands()
            if (text.endsWith(cmd.trigger)) {
                return cmd
            }
        }
        val prefix = getTriggerPrefix()
        // Translate trigger — intentionally accepts any 2-5 char alphanumeric language code
        // (e.g. "en", "fr", "zh", "pt-BR" without hyphen). Open-ended to support ISO 639 codes
        // without maintaining a hardcoded list. The AI model handles invalid codes gracefully.
        val translatePrefix = "${prefix}translate:"
        val translateIdx = text.lastIndexOf(translatePrefix)
        if (translateIdx >= 0) {
            val langPart = text.substring(translateIdx + translatePrefix.length)
            if (langPart.length in 2..5 && langPart.all { it.isLetterOrDigit() }) {
                return Command("${translatePrefix}$langPart", "Translate to language code '$langPart'.", true)
            }
        }
        return null
    }
}
