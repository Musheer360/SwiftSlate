package com.musheer360.swiftslate.manager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.musheer360.swiftslate.model.Command
import com.musheer360.swiftslate.model.CommandType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommandManagerTest {
    private lateinit var commandManager: CommandManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // Clear prefs to ensure clean state
        context.getSharedPreferences("commands", 0).edit().clear().commit()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        commandManager = CommandManager(context)
    }

    // --- findCommand ---

    @Test
    fun findCommand_withFixTrigger_returnsFixCommand() {
        val result = commandManager.findCommand("hello world?fix")
        assertNotNull(result)
        assertEquals("?fix", result!!.trigger)
        assertFalse(result.isBuiltIn)
    }

    @Test
    fun findCommand_withImproveTrigger_returnsImproveCommand() {
        val result = commandManager.findCommand("some text?improve")
        assertNotNull(result)
        assertEquals("?improve", result!!.trigger)
    }

    @Test
    fun findCommand_withUndoTrigger_returnsUndoCommand() {
        val result = commandManager.findCommand("text?undo")
        assertNotNull(result)
        assertEquals("?undo", result!!.trigger)
    }

    @Test
    fun findCommand_noTrigger_returnsNull() {
        assertNull(commandManager.findCommand("just some plain text"))
    }

    @Test
    fun findCommand_emptyText_returnsNull() {
        assertNull(commandManager.findCommand(""))
    }

    @Test
    fun findCommand_translateWithValidLangCode_returnsTranslateCommand() {
        val result = commandManager.findCommand("hello?translate:es")
        assertNotNull(result)
        assertEquals("?translate:es", result!!.trigger)
        assertTrue(result.prompt.contains("es"))
        assertTrue(result.isBuiltIn)
    }

    @Test
    fun findCommand_translateWithOneCharCode_returnsNull() {
        assertNull(commandManager.findCommand("hello?translate:x"))
    }

    @Test
    fun findCommand_translateWithSixCharCode_returnsNull() {
        assertNull(commandManager.findCommand("hello?translate:abcdef"))
    }

    @Test
    fun findCommand_longestMatchWins() {
        commandManager.saveCustomCommand(Command("?fix2", "Custom fix2 prompt"))
        val result = commandManager.findCommand("text?fix2")
        assertNotNull(result)
        assertEquals("?fix2", result!!.trigger)
        assertFalse(result.isBuiltIn)
    }

    // --- getCommands ---

    @Test
    fun getCommands_returnsFourteenBuiltInByDefault() {
        val commands = commandManager.getCommands()
        assertEquals(14, commands.size)
    }

    @Test
    fun getCommands_systemCommandsHaveIsBuiltInTrue() {
        val commands = commandManager.getCommands()
        val systemTriggers = listOf("?undo", "?copy", "?cut", "?paste", "?replace")
        val systemCommands = commands.filter { it.trigger in systemTriggers }
        assertEquals(5, systemCommands.size)
        assertTrue(systemCommands.all { it.isBuiltIn })
    }

    @Test
    fun getCommands_aiCommandsHaveIsBuiltInFalse() {
        val commands = commandManager.getCommands()
        val aiTriggers = listOf("?fix", "?improve", "?shorten", "?expand", "?formal", "?casual", "?emoji", "?human", "?reply")
        val aiCommands = commands.filter { it.trigger in aiTriggers }
        assertEquals(9, aiCommands.size)
        assertTrue(aiCommands.all { !it.isBuiltIn })
    }

    @Test
    fun getCommands_afterAddingCustom_includesIt() {
        commandManager.saveCustomCommand(Command("?myCmd", "do something"))
        val commands = commandManager.getCommands()
        assertEquals(15, commands.size)
        assertTrue(commands.any { it.trigger == "?myCmd" })
    }

    @Test
    fun getCommands_builtInsUseCurrentPrefix() {
        commandManager.setTriggerPrefix("!")
        val commands = commandManager.getCommands()
        assertTrue(commands.filter { it.isBuiltIn }.all { it.trigger.startsWith("!") })
    }

    // --- saveCustomCommand / removeCustomCommand ---

    @Test
    fun saveCustomCommand_makesFindable() {
        commandManager.saveCustomCommand(Command("?greet", "Say hello"))
        val result = commandManager.findCommand("hi?greet")
        assertNotNull(result)
        assertEquals("?greet", result!!.trigger)
    }

    @Test
    fun removeCustomCommand_makesUnfindable() {
        commandManager.saveCustomCommand(Command("?greet", "Say hello"))
        commandManager.removeCustomCommand("?greet")
        assertNull(commandManager.findCommand("hi?greet"))
    }

    @Test
    fun removeCustomCommand_nonExistentTrigger_doesNotCrash() {
        commandManager.removeCustomCommand("?nonexistent")
    }

    // --- getTriggerPrefix / setTriggerPrefix ---

    @Test
    fun getTriggerPrefix_defaultIsQuestionMark() {
        assertEquals("?", commandManager.getTriggerPrefix())
    }

    @Test
    fun setTriggerPrefix_validSymbol_returnsTrue() {
        assertTrue(commandManager.setTriggerPrefix("!"))
        assertEquals("!", commandManager.getTriggerPrefix())
    }

    @Test
    fun setTriggerPrefix_letter_returnsFalse() {
        assertFalse(commandManager.setTriggerPrefix("a"))
    }

    @Test
    fun setTriggerPrefix_digit_returnsFalse() {
        assertFalse(commandManager.setTriggerPrefix("1"))
    }

    @Test
    fun setTriggerPrefix_whitespace_returnsFalse() {
        assertFalse(commandManager.setTriggerPrefix(" "))
    }

    @Test
    fun setTriggerPrefix_multiChar_returnsFalse() {
        assertFalse(commandManager.setTriggerPrefix("!!"))
    }

    @Test
    fun setTriggerPrefix_builtInsUseNewPrefix() {
        commandManager.setTriggerPrefix("!")
        val commands = commandManager.getCommands()
        assertTrue(commands.filter { it.isBuiltIn }.all { it.trigger.startsWith("!") })
    }

    @Test
    fun setTriggerPrefix_customCommandsMigrated() {
        commandManager.saveCustomCommand(Command("?myCmd", "do something"))
        commandManager.setTriggerPrefix("!")
        val commands = commandManager.getCommands()
        assertTrue(commands.any { it.trigger == "!myCmd" })
        assertFalse(commands.any { it.trigger == "?myCmd" })
    }


    // --- write-path validation (shared by saveCustomCommand and importCommands) ---

    @Test
    fun saveCustomCommand_rejectsTriggerWithoutPrefix() {
        assertFalse(commandManager.saveCustomCommand(Command("noprefix", "do a thing")))
        assertNull(commandManager.findCommand("hello noprefix"))
    }

    @Test
    fun saveCustomCommand_rejectsPrefixOnlyTrigger() {
        assertFalse(commandManager.saveCustomCommand(Command("?", "do a thing")))
    }

    @Test
    fun saveCustomCommand_rejectsBlankPrompt() {
        assertFalse(commandManager.saveCustomCommand(Command("?thing", "   ")))
    }

    @Test
    fun saveCustomCommand_rejectsOverlongTriggerAndPrompt() {
        val longTrigger = "?" + "a".repeat(CommandManager.MAX_TRIGGER_LENGTH)
        assertFalse(commandManager.saveCustomCommand(Command(longTrigger, "p")))
        val longPrompt = "a".repeat(CommandManager.MAX_PROMPT_LENGTH + 1)
        assertFalse(commandManager.saveCustomCommand(Command("?thing", longPrompt)))
    }

    @Test
    fun saveCustomCommand_acceptsValidCommandAtTheLimits() {
        val maxTrigger = "?" + "a".repeat(CommandManager.MAX_TRIGGER_LENGTH - 1)
        assertTrue(commandManager.saveCustomCommand(Command(maxTrigger, "a".repeat(CommandManager.MAX_PROMPT_LENGTH))))
        assertNotNull(commandManager.findCommand("hello $maxTrigger"))
    }

    /** Import is strictly more lenient than save: it sanitizes what the UI would reject. */
    @Test
    fun importCommands_sanitizesWhatSaveRejects() {
        val overLongPrompt = "a".repeat(CommandManager.MAX_PROMPT_LENGTH + 10)
        val overLongTrigger = "?" + "a".repeat(CommandManager.MAX_TRIGGER_LENGTH + 5)
        val json = JSONArray()
            .put(JSONObject().put("trigger", overLongTrigger).put("prompt", overLongPrompt).put("type", "AI"))
            .put(JSONObject().put("trigger", "noprefix").put("prompt", "p").put("type", "AI"))
            .toString()
        assertTrue(commandManager.importCommands(json))
        val stored = JSONArray(commandManager.exportCommands())
        assertEquals(2, stored.length())
        val first = stored.getJSONObject(0)
        assertEquals(CommandManager.MAX_TRIGGER_LENGTH, first.getString("trigger").length)
        assertEquals(CommandManager.MAX_PROMPT_LENGTH, first.getString("prompt").length)
        val migrated = stored.getJSONObject(1)
        assertEquals("?noprefix", migrated.getString("trigger"))
    }

    @Test
    fun importCommands_dropsInvalidEntriesButKeepsValidOnes() {
        val json = JSONArray()
            .put(JSONObject().put("trigger", "   ").put("prompt", "p").put("type", "AI"))
            .put(JSONObject().put("trigger", "?ok").put("prompt", "  ").put("type", "AI"))
            .put(JSONObject().put("trigger", "?").put("prompt", "p").put("type", "AI"))
            .put(JSONObject().put("trigger", "?good").put("prompt", "keep me").put("type", "AI"))
            .toString()
        assertTrue(commandManager.importCommands(json))
        val stored = JSONArray(commandManager.exportCommands())
        assertEquals(1, stored.length())
        assertEquals("?good", stored.getJSONObject(0).getString("trigger"))
    }

    @Test
    fun importCommands_emptyArray_isValidNoop() {
        assertTrue(commandManager.importCommands("[]"))
        assertEquals("[]", commandManager.exportCommands())
    }

    @Test
    fun importCommands_keepsOnlyUpToTheMaximum() {
        val arr = JSONArray()
        for (i in 0 until (CommandManager.MAX_CUSTOM_COMMANDS + 5)) {
            arr.put(JSONObject().put("trigger", "?c$i").put("prompt", "p").put("type", "AI"))
        }
        assertTrue(commandManager.importCommands(arr.toString()))
        val stored = JSONArray(commandManager.exportCommands())
        assertEquals(CommandManager.MAX_CUSTOM_COMMANDS, stored.length())
    }

    @Test
    fun importCommands_unknownTypeDefaultsToAi() {
        val json = JSONArray().put(
            JSONObject().put("trigger", "?ok").put("prompt", "p").put("type", "SOMETHING_ELSE")
        ).toString()
        assertTrue(commandManager.importCommands(json))
        val stored = JSONArray(commandManager.exportCommands())
        assertEquals(CommandType.AI.name, stored.getJSONObject(0).getString("type"))
    }

    @Test
    fun importCommands_keepsTextReplacerType() {
        val json = JSONArray().put(
            JSONObject().put("trigger", "?sig").put("prompt", "regards").put("type", "TEXT_REPLACER")
        ).toString()
        assertTrue(commandManager.importCommands(json))
        val stored = JSONArray(commandManager.exportCommands())
        assertEquals(CommandType.TEXT_REPLACER.name, stored.getJSONObject(0).getString("type"))
    }

    @Test
    fun importCommands_rejectsMalformedJson() {
        assertFalse(commandManager.importCommands("not json at all"))
    }

    @Test
    fun importCommands_rejectsJsonWithNoUsableEntries() {
        val json = JSONArray()
            .put(JSONObject().put("trigger", "   ").put("prompt", "p").put("type", "AI"))
            .put(JSONObject().put("trigger", "?ok").put("prompt", "").put("type", "AI"))
            .toString()
        assertFalse(commandManager.importCommands(json))
    }

    // --- updateCustomCommand ---

    @Test
    fun saveCustomCommand_renamesInASingleWrite() {
        assertTrue(commandManager.saveCustomCommand(Command("?old", "original")))
        assertTrue(commandManager.saveCustomCommand(Command("?new", "changed"), replacing = "?old"))
        assertNull(commandManager.findCommand("hello ?old"))
        val found = commandManager.findCommand("hello ?new")
        assertNotNull(found)
        assertEquals("changed", found!!.prompt)
    }

    @Test
    fun saveCustomCommand_editingInPlaceDoesNotDuplicate() {
        assertTrue(commandManager.saveCustomCommand(Command("?same", "v1")))
        assertTrue(commandManager.saveCustomCommand(Command("?same", "v2")))
        assertEquals(1, commandManager.getCommands().count { it.trigger == "?same" })
        assertEquals("v2", commandManager.findCommand("x ?same")!!.prompt)
    }

    /** A rejected save must leave the existing command untouched rather than deleting it. */
    @Test
    fun saveCustomCommand_invalidReplacementKeepsOriginal() {
        assertTrue(commandManager.saveCustomCommand(Command("?keep", "original")))
        assertFalse(commandManager.saveCustomCommand(Command("bad", "x"), replacing = "?keep"))
        assertEquals("original", commandManager.findCommand("y ?keep")!!.prompt)
    }

    // --- cache invalidation (the prefix is part of the cache key) ---

    @Test
    fun changingPrefixWithNoCustomCommands_stillUpdatesBuiltIns() {
        assertNotNull(commandManager.findCommand("hello ?copy"))
        commandManager.getCommands() // populate cache
        assertTrue(commandManager.setTriggerPrefix("/"))
        assertNotNull(commandManager.findCommand("hello /copy"))
        assertNull(commandManager.findCommand("hello ?copy"))
    }
}
