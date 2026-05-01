package com.musheer360.swiftslate.manager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.musheer360.swiftslate.model.Command
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
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
        assertTrue(result.isBuiltIn)
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
        commandManager.addCustomCommand(Command("?fix2", "Custom fix2 prompt"))
        val result = commandManager.findCommand("text?fix2")
        assertNotNull(result)
        assertEquals("?fix2", result!!.trigger)
        assertFalse(result.isBuiltIn)
    }

    // --- getCommands ---

    @Test
    fun getCommands_returnsTenBuiltInByDefault() {
        val commands = commandManager.getCommands()
        assertEquals(10, commands.size)
    }

    @Test
    fun getCommands_builtInsHaveIsBuiltInTrue() {
        val commands = commandManager.getCommands()
        assertTrue(commands.all { it.isBuiltIn })
    }

    @Test
    fun getCommands_afterAddingCustom_includesIt() {
        commandManager.addCustomCommand(Command("?myCmd", "do something"))
        val commands = commandManager.getCommands()
        assertEquals(11, commands.size)
        assertTrue(commands.any { it.trigger == "?myCmd" })
    }

    @Test
    fun getCommands_builtInsUseCurrentPrefix() {
        commandManager.setTriggerPrefix("!")
        val commands = commandManager.getCommands()
        assertTrue(commands.filter { it.isBuiltIn }.all { it.trigger.startsWith("!") })
    }

    // --- addCustomCommand / removeCustomCommand ---

    @Test
    fun addCustomCommand_makesFindable() {
        commandManager.addCustomCommand(Command("?greet", "Say hello"))
        val result = commandManager.findCommand("hi?greet")
        assertNotNull(result)
        assertEquals("?greet", result!!.trigger)
    }

    @Test
    fun removeCustomCommand_makesUnfindable() {
        commandManager.addCustomCommand(Command("?greet", "Say hello"))
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
        commandManager.addCustomCommand(Command("?myCmd", "do something"))
        commandManager.setTriggerPrefix("!")
        val commands = commandManager.getCommands()
        assertTrue(commands.any { it.trigger == "!myCmd" })
        assertFalse(commands.any { it.trigger == "?myCmd" })
    }
}
