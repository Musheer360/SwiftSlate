package com.musheer360.swiftslate.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import com.musheer360.swiftslate.model.CommandType
import com.musheer360.swiftslate.ui.components.ScreenTitle
import com.musheer360.swiftslate.ui.components.SlateCard

fun checkFileExists(context: android.content.Context, pathOrUri: String): Boolean {
    if (pathOrUri.isBlank()) return false
    try {
        val uri = android.net.Uri.parse(pathOrUri)
        if (uri.scheme == "content" || uri.scheme == "android.resource") {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {}
            return true
        }
        val file = java.io.File(pathOrUri.removePrefix("file://"))
        return file.exists()
    } catch (e: Exception) {
        return false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandsScreen() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val commandManager = remember { CommandManager(context) }
    var commands by remember { mutableStateOf(commandManager.getCommands()) }
    var trigger by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    var selectedType by remember { mutableStateOf(CommandType.AI) }

    val aiPrefix = commandManager.getTriggerPrefix()
    val replacerPrefix = commandManager.getReplacerPrefix()
    val activePrefix = if (selectedType == CommandType.AI) aiPrefix else replacerPrefix

    val errorPrefixMsg = stringResource(R.string.commands_error_prefix, activePrefix)
    val errorDuplicateMsg = stringResource(R.string.commands_error_duplicate)

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {}
            prompt = it.toString()
            errorMessage = null
        }
    }

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
            Spacer(modifier = Modifier.height(16.dp))
            
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                listOf("AI Action", "Content Replacer", "File Share").forEachIndexed { index, title ->
                    SegmentedButton(
                        selected = selectedType.ordinal == index,
                        onClick = { 
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            selectedType = CommandType.entries[index]
                            errorMessage = null
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 3)
                    ) {
                        Text(title, fontSize = 12.sp)
                    }
                }
            }

            OutlinedTextField(
                value = trigger,
                onValueChange = {
                    trigger = it
                    errorMessage = null
                },
                label = { Text(stringResource(R.string.commands_trigger_label, activePrefix)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            val promptLabel = when (selectedType) {
                CommandType.AI -> stringResource(R.string.commands_prompt_label)
                CommandType.TEXT_REPLACER -> "Replacement Text (e.g., Let's connect!)"
                CommandType.FILE_SHARE -> "File Path or URI"
            }

            if (selectedType == CommandType.FILE_SHARE) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { 
                            prompt = it 
                            errorMessage = null
                        },
                        label = { Text(promptLabel) },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("Browse")
                    }
                }
            } else {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(promptLabel) },
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
            }

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
                            if (!trimmedTrigger.startsWith(activePrefix)) {
                                errorMessage = errorPrefixMsg
                                return@Button
                            }
                            if (commands.any { it.trigger == trimmedTrigger }) {
                                errorMessage = errorDuplicateMsg
                                return@Button
                            }
                            if (selectedType == CommandType.FILE_SHARE && !checkFileExists(context, prompt.trim())) {
                                errorMessage = "File could not be found or read. Please check the path."
                                return@Button
                            }
                            
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val newCommand = Command(trimmedTrigger, prompt.trim(), false, selectedType)
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
                            
                            val typeLabel = when (cmd.type) {
                                CommandType.AI -> "AI Action"
                                CommandType.TEXT_REPLACER -> "Content Replacer"
                                CommandType.FILE_SHARE -> "File Share"
                            }
                            Text(
                                text = "[$typeLabel]",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            
                            Text(
                                text = if (cmd.type == CommandType.FILE_SHARE) {
                                    val decoded = try { java.net.URLDecoder.decode(cmd.prompt, "UTF-8") } catch(e: Exception) { cmd.prompt }
                                    decoded.takeLast(40).let { if (decoded.length > 40) "...$it" else it }
                                } else {
                                    cmd.prompt
                                },
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