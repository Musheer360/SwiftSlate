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
        private const val CACHE_TTL_MS = 2_000L
    }

    // Built-in command names (without prefix) and their prompts
    private val builtInDefinitions = listOf(
        "fix" to "Fix all grammar, spelling, and punctuation errors.",
        "improve" to "Improve the clarity and readability.",
        "shorten" to "Shorten while preserving the core meaning.",
        "expand" to "Expand with more detail and context.",
        "formal" to "Rewrite in a formal, professional tone.",
        "casual" to "Rewrite in a casual, friendly tone.",
        "emoji" to "Add relevant emojis throughout.",
        "reply" to "Generate a contextual reply to this message.",
        "undo" to "Undo the last replacement and restore the original text."
    )

    fun getTriggerPrefix(): String {
        return settingsPrefs.getString(PREF_TRIGGER_PREFIX, DEFAULT_PREFIX) ?: DEFAULT_PREFIX
    }

    @Synchronized fun setTriggerPrefix(newPrefix: String): Boolean {
        if (newPrefix.length != 1 || newPrefix[0].isLetterOrDigit() || newPrefix[0].isWhitespace()) return false
        val oldPrefix = getTriggerPrefix()
        if (oldPrefix == newPrefix) return true
        // Migrate custom command triggers
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = JSONArray(customStr)
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val oldTrigger = obj.getString("trigger")
            val migrated = if (oldTrigger.startsWith(oldPrefix)) {
                newPrefix + oldTrigger.removePrefix(oldPrefix)
            } else oldTrigger
            val newObj = JSONObject()
            newObj.put("trigger", migrated)
            newObj.put("prompt", obj.getString("prompt"))
            newObj.put("type", obj.optString("type", CommandType.AI.name))
            newArr.put(newObj)
        }
        // Write commands first, then prefix. If process dies between the two:
        // - Commands have new prefix, settings has old → built-ins use old prefix, custom use new.
        //   Custom commands won't match until next setTriggerPrefix call. Acceptable degradation.
        prefs.edit().putString("custom_commands", newArr.toString()).apply()
        settingsPrefs.edit().putString(PREF_TRIGGER_PREFIX, newPrefix).apply()
        cachedCommands = null
        return true
    }

    private fun getBuiltInCommands(): List<Command> {
        val prefix = getTriggerPrefix()
        return builtInDefinitions.map { (name, prompt) -> Command("$prefix$name", prompt, true) }
    }

    @Synchronized fun getCommands(): List<Command> {
        val now = System.currentTimeMillis()
        val cached = cachedCommands
        if (cached != null && now - cacheTimestamp < CACHE_TTL_MS) return cached
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = JSONArray(customStr)
        val customCommands = mutableListOf<Command>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            customCommands.add(Command(obj.getString("trigger"), obj.getString("prompt"), false,
                try { CommandType.valueOf(obj.optString("type", CommandType.AI.name)) } catch (_: Exception) { CommandType.AI }))
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
