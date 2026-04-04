package com.musheer360.swiftslate.manager

import android.content.Context
import android.content.SharedPreferences
import com.musheer360.swiftslate.model.Command
import org.json.JSONArray
import org.json.JSONObject

class CommandManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("commands", Context.MODE_PRIVATE)
    private val settingsPrefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_PREFIX = "!"
        const val PREF_TRIGGER_PREFIX = "trigger_prefix"
        const val DEFAULT_REPLACER_PREFIX = "/"
        const val PREF_REPLACER_PREFIX = "replacer_prefix"
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

    fun setTriggerPrefix(newPrefix: String): Boolean {
        if (newPrefix.length != 1 || newPrefix[0].isLetterOrDigit() || newPrefix[0].isWhitespace()) return false
        val oldPrefix = getTriggerPrefix()
        if (oldPrefix == newPrefix) return true
        if (newPrefix == getReplacerPrefix()) return false // Prevent collision
        
        settingsPrefs.edit().putString(PREF_TRIGGER_PREFIX, newPrefix).commit()
        
        migratePrefix(oldPrefix, newPrefix, com.musheer360.swiftslate.model.CommandType.AI)
        return true
    }

    fun getReplacerPrefix(): String {
        return settingsPrefs.getString(PREF_REPLACER_PREFIX, DEFAULT_REPLACER_PREFIX) ?: DEFAULT_REPLACER_PREFIX
    }

    fun setReplacerPrefix(newPrefix: String): Boolean {
        if (newPrefix.length != 1 || newPrefix[0].isLetterOrDigit() || newPrefix[0].isWhitespace()) return false
        val oldPrefix = getReplacerPrefix()
        if (oldPrefix == newPrefix) return true
        if (newPrefix == getTriggerPrefix()) return false // Prevent collision
        
        settingsPrefs.edit().putString(PREF_REPLACER_PREFIX, newPrefix).commit()
        
        migratePrefix(oldPrefix, newPrefix, null) // Migrate all replacer types
        return true
    }

    private fun migratePrefix(oldPrefix: String, newPrefix: String, filterType: com.musheer360.swiftslate.model.CommandType?) {
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = JSONArray(customStr)
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val typeStr = obj.optString("type", com.musheer360.swiftslate.model.CommandType.AI.name)
            val type = try { com.musheer360.swiftslate.model.CommandType.valueOf(typeStr) } catch (e: Exception) { com.musheer360.swiftslate.model.CommandType.AI }
            
            val shouldMigrate = if (filterType == com.musheer360.swiftslate.model.CommandType.AI) {
                type == com.musheer360.swiftslate.model.CommandType.AI
            } else {
                type == com.musheer360.swiftslate.model.CommandType.TEXT_REPLACER || type == com.musheer360.swiftslate.model.CommandType.FILE_SHARE
            }

            val oldTrigger = obj.getString("trigger")
            val migrated = if (shouldMigrate && oldTrigger.startsWith(oldPrefix)) {
                newPrefix + oldTrigger.removePrefix(oldPrefix)
            } else oldTrigger
            
            val newObj = JSONObject()
            newObj.put("trigger", migrated)
            newObj.put("prompt", obj.getString("prompt"))
            newObj.put("type", type.name)
            newArr.put(newObj)
        }
        prefs.edit().putString("custom_commands", newArr.toString()).apply()
    }

    private fun getBuiltInCommands(): List<Command> {
        val prefix = getTriggerPrefix()
        return builtInDefinitions.map { (name, prompt) -> Command("$prefix$name", prompt, true) }
    }

    fun getCommands(): List<Command> {
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = JSONArray(customStr)
        val customCommands = mutableListOf<Command>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val typeStr = obj.optString("type", com.musheer360.swiftslate.model.CommandType.AI.name)
            val type = try { com.musheer360.swiftslate.model.CommandType.valueOf(typeStr) } catch (e: Exception) { com.musheer360.swiftslate.model.CommandType.AI }
            customCommands.add(Command(obj.getString("trigger"), obj.getString("prompt"), false, type))
        }
        return getBuiltInCommands() + customCommands
    }

    fun addCustomCommand(command: Command) {
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = JSONArray(customStr)
        val newObj = JSONObject()
        newObj.put("trigger", command.trigger)
        newObj.put("prompt", command.prompt)
        newObj.put("type", command.type.name)
        arr.put(newObj)
        prefs.edit().putString("custom_commands", arr.toString()).apply()
    }

    fun removeCustomCommand(trigger: String) {
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
    }

    fun findCommand(text: String): Command? {
        val commands = getCommands()
        for (cmd in commands.sortedByDescending { it.trigger.length }) {
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
