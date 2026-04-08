package com.musheer360.swiftslate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.manager.CommandManager
import com.musheer360.swiftslate.model.Command
import com.musheer360.swiftslate.model.CommandType
import com.musheer360.swiftslate.ui.components.ScreenTitle
import com.musheer360.swiftslate.ui.components.SectionHeader
import com.musheer360.swiftslate.ui.components.SlateCard
import com.musheer360.swiftslate.ui.components.SlateDivider
import com.musheer360.swiftslate.ui.components.SlateItemCard
import com.musheer360.swiftslate.ui.components.SlateTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandsScreen() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val commandManager = remember { CommandManager(context) }
    var commands by remember { mutableStateOf(commandManager.getCommands()) }
    // Display order: built-in first, then custom in insertion order
    val displayCommands by remember(commands) {
        mutableStateOf(commands.filter { it.isBuiltIn } + commands.filter { !it.isBuiltIn })
    }
    var trigger by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedType by remember { mutableStateOf(CommandType.AI) }
    var editingTrigger by remember { mutableStateOf<String?>(null) }
    val prefix = commandManager.getTriggerPrefix()
    val errorPrefixMsg = stringResource(R.string.commands_error_prefix, prefix)
    val errorDuplicateMsg = stringResource(R.string.commands_error_duplicate)
    val errorEmptyTrigger = stringResource(R.string.commands_error_empty_trigger)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        ScreenTitle(stringResource(R.string.commands_title))

        SectionHeader(stringResource(R.string.commands_add_custom_title))
        SlateCard {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                SegmentedButton(
                    selected = selectedType == CommandType.AI,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        selectedType = CommandType.AI
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                        activeBorderColor = MaterialTheme.colorScheme.primary,
                        inactiveContainerColor = MaterialTheme.colorScheme.surface,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        inactiveBorderColor = MaterialTheme.colorScheme.outline
                    )
                ) {
                    Text(stringResource(R.string.commands_type_ai))
                }
                SegmentedButton(
                    selected = selectedType == CommandType.TEXT_REPLACER,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        selectedType = CommandType.TEXT_REPLACER
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                        activeBorderColor = MaterialTheme.colorScheme.primary,
                        inactiveContainerColor = MaterialTheme.colorScheme.surface,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        inactiveBorderColor = MaterialTheme.colorScheme.outline
                    )
                ) {
                    Text(stringResource(R.string.commands_type_replacer))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            SlateTextField(
                value = trigger,
                onValueChange = {
                    trigger = it
                    errorMessage = null
                },
                label = { Text(stringResource(R.string.commands_trigger_label, prefix)) },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(if (selectedType == CommandType.AI) stringResource(R.string.commands_prompt_label) else stringResource(R.string.commands_replacement_label)) },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
            errorMessage?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (editingTrigger != null) {
                TextButton(
                    onClick = {
                        trigger = ""
                        prompt = ""
                        errorMessage = null
                        editingTrigger = null
                        selectedType = CommandType.AI
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.commands_cancel))
                }
            }
            Button(
                onClick = {
                    val trimmedTrigger = trigger.trim()
                    if (trimmedTrigger.isNotBlank() && prompt.isNotBlank()) {
                        if (!trimmedTrigger.startsWith(prefix)) {
                            errorMessage = errorPrefixMsg
                            return@Button
                        }
                        if (trimmedTrigger == prefix || trimmedTrigger.length <= prefix.length) {
                            errorMessage = errorEmptyTrigger
                            return@Button
                        }
                        if (commands.any { it.trigger == trimmedTrigger && it.trigger != editingTrigger }) {
                            errorMessage = errorDuplicateMsg
                            return@Button
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (editingTrigger != null) {
                            commandManager.removeCustomCommand(editingTrigger!!)
                        }
                        val newCommand = Command(trimmedTrigger, prompt.trim(), false, selectedType)
                        commandManager.addCustomCommand(newCommand)
                        commands = commandManager.getCommands()
                        trigger = ""
                        prompt = ""
                        errorMessage = null
                        editingTrigger = null
                        selectedType = CommandType.AI
                    }
                },
                enabled = trigger.isNotBlank() && trigger.trim() != prefix && prompt.isNotBlank(),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            ) {
                Text(if (editingTrigger != null) stringResource(R.string.commands_save_command) else stringResource(R.string.commands_add_command))
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (displayCommands.isNotEmpty()) {
            SectionHeader(stringResource(R.string.commands_title))
            SlateCard {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    itemsIndexed(displayCommands, key = { _, cmd -> cmd.trigger }) { index, cmd ->
                        SlateItemCard {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = cmd.trigger,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (cmd.isBuiltIn) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.commands_built_in),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (cmd.type == CommandType.TEXT_REPLACER) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.commands_type_replacer),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = cmd.prompt,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!cmd.isBuiltIn) {
                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        trigger = cmd.trigger
                                        prompt = cmd.prompt
                                        selectedType = cmd.type
                                        editingTrigger = cmd.trigger
                                        errorMessage = null
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.commands_edit_command),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        commandManager.removeCustomCommand(cmd.trigger)
                                        if (editingTrigger == cmd.trigger) {
                                            trigger = ""
                                            prompt = ""
                                            errorMessage = null
                                            editingTrigger = null
                                            selectedType = CommandType.AI
                                        }
                                        commands = commandManager.getCommands()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.commands_delete_command),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
