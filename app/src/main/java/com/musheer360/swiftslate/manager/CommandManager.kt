package com.musheer360.swiftslate.manager

import android.content.Context
import android.content.SharedPreferences
import com.musheer360.swiftslate.model.Command
import com.musheer360.swiftslate.model.CommandType
import org.json.JSONArray
import org.json.JSONObject

class CommandManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("commands", Context.MODE_PRIVATE)
    private val settingsPrefs: SharedPreferences = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Volatile
    private var cachedCommands: List<Command>? = null

    companion object {
        const val DEFAULT_PREFIX = "?"
        const val PREF_TRIGGER_PREFIX = "trigger_prefix"
    }

    private fun getBuiltInDefinitions(): List<Pair<String, String>> {
        val langSetting = settingsPrefs.getString("builtin_lang", "auto") ?: "auto"
        val langToTry = if (langSetting == "auto") {
            java.util.Locale.getDefault().let { "${it.language}_${it.country}".lowercase() }
        } else {
            langSetting
        }

        var jsonContent: String? = loadJsonFromAsset("builtInDefinitions/$langToTry.json")
        if (jsonContent == null) {
            val langOnly = langToTry.substringBefore('_')
            jsonContent = loadJsonFromAsset("builtInDefinitions/$langOnly.json")
        }
        if (jsonContent == null) {
            jsonContent = loadJsonFromAsset("builtInDefinitions/en_us.json")
        }

        val list = mutableListOf<Pair<String, String>>()
        if (jsonContent != null) {
            try {
                val jsonObject = JSONObject(jsonContent)
                val order = listOf("fix", "improve", "shorten", "expand", "formal", "casual", "emoji", "reply", "undo")
                for (key in order) {
                    if (jsonObject.has(key)) {
                        list.add(key to jsonObject.getString(key))
                    }
                }
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!order.contains(key)) {
                        list.add(key to jsonObject.getString(key))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return list
    }

    private fun loadJsonFromAsset(path: String): String? {
        return try {
            appContext.assets.open(path).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    fun getTriggerPrefix(): String {
        return settingsPrefs.getString(PREF_TRIGGER_PREFIX, DEFAULT_PREFIX) ?: DEFAULT_PREFIX
    }

    fun setTriggerPrefix(newPrefix: String): Boolean {
        if (newPrefix.length != 1 || newPrefix[0].isLetterOrDigit() || newPrefix[0].isWhitespace()) return false
        val oldPrefix = getTriggerPrefix()
        if (oldPrefix == newPrefix) return true
        // Write prefix FIRST (synchronous) so built-ins work immediately if process dies mid-migration
        settingsPrefs.edit().putString(PREF_TRIGGER_PREFIX, newPrefix).commit()
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
        prefs.edit().putString("custom_commands", newArr.toString()).apply()
        cachedCommands = null
        return true
    }

    private fun getBuiltInCommands(): List<Command> {
        val prefix = getTriggerPrefix()
        return getBuiltInDefinitions().map { (name, prompt) -> Command("$prefix$name", prompt, true) }
    }

    fun getCommands(): List<Command> {
        cachedCommands?.let { return it }
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
        return result
    }

    fun addCustomCommand(command: Command) {
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
        cachedCommands = null
    }

    fun exportCommands(): String {
        return prefs.getString("custom_commands", "[]") ?: "[]"
    }

    fun importCommands(json: String): Boolean {
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
