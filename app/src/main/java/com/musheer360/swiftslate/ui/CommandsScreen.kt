package com.musheer360.swiftslate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.musheer360.swiftslate.ui.components.ScreenTitle
import com.musheer360.swiftslate.ui.components.SlateCard

@Composable
fun CommandsScreen() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val commandManager = remember { CommandManager(context) }
    var commands by remember { mutableStateOf(commandManager.getCommands()) }
    var trigger by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val prefix = commandManager.getTriggerPrefix()
    val errorPrefixMsg = stringResource(R.string.commands_error_prefix, prefix)
    val errorDuplicateMsg = stringResource(R.string.commands_error_duplicate)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 24.dp)
    ) {
        ScreenTitle(stringResource(R.string.commands_title))

        SlateCard {
            Text(
                text = stringResource(R.string.commands_add_custom_title),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = trigger,
                onValueChange = {
                    trigger = it
                    errorMessage = null
                },
                label = { Text(stringResource(R.string.commands_trigger_label, prefix)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(stringResource(R.string.commands_prompt_label)) },
                modifier = Modifier.fillMaxWidth().height(100.dp),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = {
                        val trimmedTrigger = trigger.trim()
                        if (trimmedTrigger.isNotBlank() && prompt.isNotBlank()) {
                            if (!trimmedTrigger.startsWith(prefix)) {
                                errorMessage = errorPrefixMsg
                                return@Button
                            }
                            if (commands.any { it.trigger == trimmedTrigger }) {
                                errorMessage = errorDuplicateMsg
                                return@Button
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val newCommand = Command(trimmedTrigger, prompt.trim(), false)
                            commandManager.addCustomCommand(newCommand)
                            commands = commandManager.getCommands()
                            trigger = ""
                            prompt = ""
                            errorMessage = null
                        }
                    },
                    enabled = trigger.isNotBlank() && prompt.isNotBlank()
                ) {
                    Text(stringResource(R.string.commands_add_command))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(commands) { cmd ->
                SlateCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = cmd.trigger,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = cmd.prompt,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (cmd.isBuiltIn) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.commands_built_in),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                        Row {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                trigger = cmd.trigger
                                prompt = cmd.prompt
                            }) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Duplicate command",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (!cmd.isBuiltIn) {
                                IconButton(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    commandManager.removeCustomCommand(cmd.trigger)
                                    commands = commandManager.getCommands()
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.commands_delete_command),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}