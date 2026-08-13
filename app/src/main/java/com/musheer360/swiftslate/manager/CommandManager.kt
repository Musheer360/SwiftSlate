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
    /**
     * Raw JSON [cachedCommands] was parsed from, so an expired TTL can be revalidated with a
     * string compare instead of re-parsing every command. The TTL has to stay: the UI and the
     * accessibility service hold separate CommandManager instances in one process, and this is
     * how the service notices commands edited in the UI. But it fired on the service's
     * keystroke path, so the parse ran again every 5s of typing for no change.
     */
    @Volatile
    private var cachedCommandsJson: String? = null
    /**
     * Prefix [cachedCommands] was built with. Part of the cache key because built-in triggers
     * are derived from it — with a JSON-only check, changing the prefix while no custom commands
     * exist leaves custom_commands as "[]" and the other instance would keep serving built-ins
     * under the old prefix forever.
     */
    @Volatile
    private var cachedPrefix: String? = null
    // Typed prefs reads can throw ClassCastException on a corrupted store; this runs on the
    // accessibility service's bind path, where an escape would kill the whole process (#125).
    private var aiCommandsSeeded =
        try { prefs.getBoolean("ai_commands_seeded", false) } catch (_: Exception) { false }

    companion object {
        const val DEFAULT_PREFIX = "?"
        const val PREF_TRIGGER_PREFIX = "trigger_prefix"
        private const val CACHE_TTL_MS = 5_000L

        /** Limits enforced on every write path — see [isValidCommand] / [importCommands]. */
        const val MAX_TRIGGER_LENGTH = 50
        const val MAX_PROMPT_LENGTH = 5_000
        const val MAX_CUSTOM_COMMANDS = 100

        /**
         * Whether a custom command is storable. Applied by both [saveCustomCommand] and
         * [importCommands]: the limits used to live only in the import path, so the UI could
         * create commands that the app's own exported backup would then refuse to import.
         */
        fun isValidCommand(trigger: String, prompt: String, prefix: String): Boolean =
            trigger.isNotBlank() && prompt.isNotBlank() &&
                trigger.length <= MAX_TRIGGER_LENGTH && prompt.length <= MAX_PROMPT_LENGTH &&
                trigger.startsWith(prefix) && trigger.length > prefix.length
    }

    // System commands — local operations that cannot be edited or deleted
    private val systemDefinitions = listOf(
        "undo" to "Undo the last replacement and restore the original text.",
        "copy" to "Copy the text to clipboard.",
        "cut" to "Cut the text to clipboard.",
        "paste" to "Paste from clipboard.",
        "replace" to "Replace text with clipboard content."
    )

    // Default AI commands — seeded into custom commands on first run so users can edit/delete them
    private val defaultAiDefinitions = listOf(
        "fix" to "Fix grammar, spelling, and punctuation errors.",
        "improve" to "Rewrite to improve clarity, flow, and coherence.",
        "shorten" to "Rewrite to be more concise while preserving the core meaning.",
        "expand" to "Rewrite with more detail. Elaborate only on what is stated or widely known \u2014 do not fabricate information.",
        "formal" to "Rewrite in a formal, professional tone.",
        "casual" to "Rewrite in a casual, friendly tone.",
        "emoji" to "Add relevant emojis throughout.",
        "human" to "Rewrite to sound naturally human, not AI-generated. Never use emdashes or semicolons, use commas or periods instead. Drop AI clichés and filler phrases. Use contractions, everyday words, and varied sentence lengths. Keep all facts, names, and numbers intact.",
        "reply" to "Generate a contextual reply to this message."
    )

    /** Drops the cache and its validity key so the next [getCommands] rebuilds from prefs. */
    private fun invalidateCache() {
        cachedCommands = null
        cachedCommandsJson = null
        cachedPrefix = null
    }

    fun getTriggerPrefix(): String {
        return try {
            settingsPrefs.getString(PREF_TRIGGER_PREFIX, DEFAULT_PREFIX) ?: DEFAULT_PREFIX
        } catch (_: Exception) {
            DEFAULT_PREFIX
        }
    }

    @Synchronized fun setTriggerPrefix(newPrefix: String): Boolean {
        if (newPrefix.length != 1 || newPrefix[0].isLetterOrDigit() || newPrefix[0].isWhitespace()) return false
        // Write prefix first so crash between writes is self-healing on retry
        settingsPrefs.edit().putString(PREF_TRIGGER_PREFIX, newPrefix).apply()
        // Migrate custom command triggers — idempotent: always fix commands not matching current prefix
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = try { JSONArray(customStr) } catch (_: Exception) {
            prefs.edit().putString("custom_commands", "[]").apply()
            invalidateCache()
            return true
        }
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val oldTrigger = obj.optString("trigger", "")
            val prompt = obj.optString("prompt", "")
            if (oldTrigger.isEmpty() || prompt.isEmpty()) continue
            val migrated = if (!oldTrigger.startsWith(newPrefix)) {
                // Strip any single-char non-alphanumeric prefix, then apply new prefix
                val stripped = if (!oldTrigger[0].isLetterOrDigit()) oldTrigger.substring(1) else oldTrigger
                newPrefix + stripped
            } else oldTrigger
            val newObj = JSONObject()
            newObj.put("trigger", migrated)
            newObj.put("prompt", prompt)
            newObj.put("type", obj.optString("type", CommandType.AI.name))
            newArr.put(newObj)
        }
        prefs.edit().putString("custom_commands", newArr.toString()).apply()
        invalidateCache()
        return true
    }

    private fun getBuiltInCommands(): List<Command> {
        val prefix = getTriggerPrefix()
        return systemDefinitions.map { (name, prompt) -> Command("$prefix$name", prompt, true) }
    }

    private fun seedDefaultAiCommands() {
        val prefix = getTriggerPrefix()
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = try { JSONArray(customStr) } catch (_: Exception) { JSONArray() }
        val existingTriggers = (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it)?.optString("trigger")?.takeIf { t -> t.isNotEmpty() } }
            .toSet()
        var added = false
        for ((name, prompt) in defaultAiDefinitions) {
            val trigger = "$prefix$name"
            if (trigger !in existingTriggers) {
                val obj = JSONObject()
                obj.put("trigger", trigger)
                obj.put("prompt", prompt)
                obj.put("type", CommandType.AI.name)
                arr.put(obj)
                added = true
            }
        }
        val editor = prefs.edit()
        if (added) {
            editor.putString("custom_commands", arr.toString())
            invalidateCache()
        }
        editor.putBoolean("ai_commands_seeded", true).apply()
        aiCommandsSeeded = true
    }

    @Volatile
    private var migrating = false

    @Synchronized fun getCommands(): List<Command> {
        if (!aiCommandsSeeded) {
            seedDefaultAiCommands()
        }
        val now = System.currentTimeMillis()
        val cached = cachedCommands
        if (cached != null && now - cacheTimestamp < CACHE_TTL_MS) return cached
        val prefix = getTriggerPrefix()
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        if (cached != null && customStr == cachedCommandsJson && prefix == cachedPrefix) {
            cacheTimestamp = now
            return cached
        }
        // Guard against a corrupted store — an unhandled JSONException here would
        // crash the accessibility service on every text-change event with no recovery.
        val arr = try { JSONArray(customStr) } catch (_: Exception) {
            prefs.edit().putString("custom_commands", "[]").apply()
            JSONArray()
        }
        val customCommands = mutableListOf<Command>()
        var needsMigration = false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val trigger = obj.optString("trigger", "")
            val prompt = obj.optString("prompt", "")
            if (trigger.isEmpty() || prompt.isEmpty()) continue
            if (!trigger.startsWith(prefix)) needsMigration = true
            customCommands.add(Command(trigger, prompt, false,
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
        cachedCommandsJson = customStr
        cachedPrefix = prefix
        cacheTimestamp = System.currentTimeMillis()
        return result
    }

    /**
     * Stores [command], replacing [replacing] in the same write.
     *
     * [replacing] defaults to the command's own trigger, which makes this an upsert; pass the
     * old trigger to rename. The Commands screen used to call removeCustomCommand() then
     * saveCustomCommand() when saving a rename — two separate prefs writes, so a failure between
     * them left the command deleted and not re-added.
     *
     * Returns false if the command is not storable, in which case nothing is written.
     */
    @Synchronized fun saveCustomCommand(command: Command, replacing: String = command.trigger): Boolean {
        if (!isValidCommand(command.trigger, command.prompt, getTriggerPrefix())) return false
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = try { JSONArray(customStr) } catch (_: Exception) { JSONArray() }
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val trigger = obj.optString("trigger")
            if (trigger != replacing && trigger != command.trigger) {
                newArr.put(obj)
            }
        }
        val newObj = JSONObject()
        newObj.put("trigger", command.trigger)
        newObj.put("prompt", command.prompt)
        newObj.put("type", command.type.name)
        newArr.put(newObj)
        prefs.edit().putString("custom_commands", newArr.toString()).apply()
        invalidateCache()
        return true
    }

    @Synchronized fun removeCustomCommand(trigger: String) {
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = try { JSONArray(customStr) } catch (_: Exception) { JSONArray() }
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("trigger") != trigger) {
                newArr.put(obj)
            }
        }
        prefs.edit().putString("custom_commands", newArr.toString()).apply()
        invalidateCache()
    }

    @Synchronized fun exportCommands(): String {
        return prefs.getString("custom_commands", "[]") ?: "[]"
    }

    /**
     * Imports commands from a backup, sanitizing entries instead of rejecting the whole file.
     *
     * Only a genuinely malformed file (unparseable JSON) fails hard. Individual entries are
     * cleaned so that backups from older versions — which could store prompts longer than
     * [MAX_PROMPT_LENGTH] or triggers with a stale prefix — still restore: triggers are
     * truncated to [MAX_TRIGGER_LENGTH] and migrated to the current prefix, prompts truncated
     * to [MAX_PROMPT_LENGTH], unknown types default to [CommandType.AI], and unusable entries
     * (blank trigger/prompt) are dropped. The result is capped at [MAX_CUSTOM_COMMANDS].
     */
    @Synchronized fun importCommands(json: String): Boolean {
        return try {
            val arr = JSONArray(json)
            val prefix = getTriggerPrefix()
            val cleaned = JSONArray()
            for (i in 0 until arr.length()) {
                if (cleaned.length() >= MAX_CUSTOM_COMMANDS) break
                val obj = arr.optJSONObject(i) ?: continue
                var trigger = obj.optString("trigger", "").trim()
                val prompt = obj.optString("prompt", "").take(MAX_PROMPT_LENGTH)
                if (trigger.isEmpty() || prompt.isBlank()) continue
                if (!trigger.startsWith(prefix)) {
                    // Same migration as setTriggerPrefix: strip any leading non-alphanumeric
                    // char, then apply the current prefix.
                    val stripped = if (!trigger[0].isLetterOrDigit()) trigger.substring(1) else trigger
                    trigger = prefix + stripped
                }
                trigger = trigger.take(MAX_TRIGGER_LENGTH)
                if (trigger.length <= prefix.length) continue
                val type = obj.optString("type", CommandType.AI.name)
                val out = JSONObject()
                out.put("trigger", trigger)
                out.put("prompt", prompt)
                out.put("type",
                    if (type == CommandType.TEXT_REPLACER.name) CommandType.TEXT_REPLACER.name else CommandType.AI.name)
                cleaned.put(out)
            }
            if (arr.length() > 0 && cleaned.length() == 0) return false
            prefs.edit().putString("custom_commands", cleaned.toString()).apply()
            invalidateCache()
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
            if (langPart.length in 2..5 && langPart.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) {
                return Command("${translatePrefix}$langPart", "Translate to language code '$langPart'.", true)
            }
        }
        return null
    }
}
