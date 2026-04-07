package com.musheer360.swiftslate.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musheer360.swiftslate.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.musheer360.swiftslate.manager.CommandManager
import com.musheer360.swiftslate.ui.components.ScreenTitle
import com.musheer360.swiftslate.ui.components.SectionHeader
import com.musheer360.swiftslate.ui.components.SlateCard
import com.musheer360.swiftslate.ui.components.SlateTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: androidx.navigation.NavController? = null) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    val scope = rememberCoroutineScope()
    var saveEndpointJob by remember { mutableStateOf<Job?>(null) }
    var saveModelJob by remember { mutableStateOf<Job?>(null) }

    var providerType by remember { mutableStateOf(prefs.getString("provider_type", "gemini") ?: "gemini") }
    var providerExpanded by remember { mutableStateOf(false) }

    var selectedModel by remember { mutableStateOf(prefs.getString("model", "gemini-2.5-flash-lite") ?: "gemini-2.5-flash-lite") }
    var modelExpanded by remember { mutableStateOf(false) }
    val geminiModels = listOf("gemini-2.5-flash-lite", "gemini-3-flash-preview", "gemini-3.1-flash-lite-preview")

    var groqModel by remember { mutableStateOf(prefs.getString("groq_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile") }
    var groqModelExpanded by remember { mutableStateOf(false) }
    val groqModels = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "openai/gpt-oss-120b", "openai/gpt-oss-20b", "meta-llama/llama-4-scout-17b-16e-instruct")

    var customEndpoint by remember { mutableStateOf(prefs.getString("custom_endpoint", "") ?: "") }
    var customModel by remember { mutableStateOf(prefs.getString("custom_model", "") ?: "") }
    var endpointError by remember { mutableStateOf<String?>(null) }

    val commandManager = remember { CommandManager(context) }
    var triggerPrefix by remember { mutableStateOf(commandManager.getTriggerPrefix()) }
    var prefixError by remember { mutableStateOf<String?>(null) }

    val prefixErrorLength = stringResource(R.string.settings_prefix_error_length)
    val prefixErrorWhitespace = stringResource(R.string.settings_prefix_error_whitespace)
    val prefixErrorAlphanumeric = stringResource(R.string.settings_prefix_error_alphanumeric)
    val endpointErrorScheme = stringResource(R.string.settings_endpoint_error_scheme)
    val endpointErrorSpaces = stringResource(R.string.settings_endpoint_error_spaces)

    var backupMessage by remember { mutableStateOf<String?>(null) }
    var backupSuccess by remember { mutableStateOf(false) }
    val exportSuccessMsg = stringResource(R.string.backup_export_success)
    val importSuccessMsg = stringResource(R.string.backup_import_success)
    val importErrorMsg = stringResource(R.string.backup_import_error)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(commandManager.exportCommands().toByteArray())
                }
                backupMessage = exportSuccessMsg
                backupSuccess = true
            } catch (_: Exception) {
                backupMessage = importErrorMsg
                backupSuccess = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                val json = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader ->
                    val text = reader.readText()
                    if (text.length > 1_000_000) null else text
                } ?: ""
                if (commandManager.importCommands(json)) {
                    backupMessage = importSuccessMsg
                    backupSuccess = true
                } else {
                    backupMessage = importErrorMsg
                    backupSuccess = false
                }
            } catch (_: Exception) {
                backupMessage = importErrorMsg
                backupSuccess = false
            }
        }
    }

    // Built-in language settings
    var builtinLang by remember { mutableStateOf(prefs.getString("builtin_lang", "auto") ?: "auto") }
    var builtinLangExpanded by remember { mutableStateOf(false) }
    val builtinLangOptions = listOf(
        "auto" to stringResource(R.string.settings_builtin_lang_auto),
        "en_us" to stringResource(R.string.settings_builtin_lang_en),
        "fr" to stringResource(R.string.settings_builtin_lang_fr),
        "es" to stringResource(R.string.settings_builtin_lang_es),
        "de" to stringResource(R.string.settings_builtin_lang_de),
        "it" to stringResource(R.string.settings_builtin_lang_it),
        "pt" to stringResource(R.string.settings_builtin_lang_pt),
        "zh" to stringResource(R.string.settings_builtin_lang_zh),
        "ja" to stringResource(R.string.settings_builtin_lang_ja),
        "ru" to stringResource(R.string.settings_builtin_lang_ru)
    )

    // Spinner style settings
    var spinnerStyle by remember { mutableStateOf(prefs.getString("spinner_style", "default") ?: "default") }
    var spinnerStyleExpanded by remember { mutableStateOf(false) }
    val spinnerStyleOptions = listOf(
        "default" to stringResource(R.string.settings_spinner_style_default),
        "dots" to stringResource(R.string.settings_spinner_style_dots),
        "squares" to stringResource(R.string.settings_spinner_style_squares),
        "none" to stringResource(R.string.settings_spinner_style_none)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        ScreenTitle(stringResource(R.string.settings_title))

        SectionHeader(stringResource(R.string.settings_provider_title))
        SlateCard {
            ExposedDropdownMenuBox(
                expanded = providerExpanded,
                onExpandedChange = { providerExpanded = !providerExpanded }
            ) {
                SlateTextField(
                    value = when (providerType) {
                        "gemini" -> stringResource(R.string.settings_provider_gemini)
                        "groq" -> stringResource(R.string.settings_provider_groq)
                        else -> stringResource(R.string.settings_provider_custom)
                    },
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_provider_gemini)) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            providerType = "gemini"
                            prefs.edit().putString("provider_type", "gemini").remove("structured_output_disabled").apply()
                            providerExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_provider_groq)) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            providerType = "groq"
                            prefs.edit().putString("provider_type", "groq").remove("structured_output_disabled").apply()
                            providerExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_provider_custom)) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            providerType = "custom"
                            prefs.edit().putString("provider_type", "custom").remove("structured_output_disabled").apply()
                            providerExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (providerType == "gemini") {
            SectionHeader(stringResource(R.string.settings_model_title))
            SlateCard {
                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = !modelExpanded }
                ) {
                    SlateTextField(
                        value = selectedModel,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false }
                    ) {
                        geminiModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selectedModel = model
                                    prefs.edit().putString("model", model).remove("structured_output_disabled").apply()
                                    modelExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        } else if (providerType == "groq") {
            SectionHeader(stringResource(R.string.settings_model_title))
            SlateCard {
                ExposedDropdownMenuBox(
                    expanded = groqModelExpanded,
                    onExpandedChange = { groqModelExpanded = !groqModelExpanded }
                ) {
                    SlateTextField(
                        value = groqModel,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = groqModelExpanded,
                        onDismissRequest = { groqModelExpanded = false }
                    ) {
                        groqModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    groqModel = model
                                    prefs.edit().putString("groq_model", model).remove("structured_output_disabled").apply()
                                    groqModelExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        } else {
            SectionHeader(stringResource(R.string.settings_endpoint_title))
            SlateCard {
                Text(
                    text = stringResource(R.string.settings_endpoint_desc),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                SlateTextField(
                    value = customEndpoint,
                    onValueChange = {
                        customEndpoint = it
                        endpointError = when {
                            it.isBlank() -> null
                            it.contains(" ") -> endpointErrorSpaces
                            it.startsWith("https://") -> null
                            it.startsWith("http://") -> {
                                val host = try { java.net.URL(it).host } catch (_: Exception) { "" }
                                if (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2") null
                                else endpointErrorScheme
                            }
                            else -> endpointErrorScheme
                        }
                        if (endpointError == null) {
                            saveEndpointJob?.cancel()
                            saveEndpointJob = scope.launch {
                                delay(500)
                                prefs.edit().putString("custom_endpoint", it).remove("structured_output_disabled").apply()
                            }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.settings_endpoint_placeholder)) },
                    isError = endpointError != null
                )
                endpointError?.let { msg ->
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionHeader(stringResource(R.string.settings_model_title))
            SlateCard {
                Text(
                    text = stringResource(R.string.settings_model_desc),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                SlateTextField(
                    value = customModel,
                    onValueChange = {
                        customModel = it
                        saveModelJob?.cancel()
                        saveModelJob = scope.launch {
                            delay(500)
                            prefs.edit().putString("custom_model", it).remove("structured_output_disabled").apply()
                        }
                    },
                    placeholder = { Text(stringResource(R.string.settings_model_placeholder)) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SlateCard {
            Text(
                text = stringResource(R.string.settings_builtin_lang_title),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            ExposedDropdownMenuBox(
                expanded = builtinLangExpanded,
                onExpandedChange = { builtinLangExpanded = !builtinLangExpanded }
            ) {
                OutlinedTextField(
                    value = builtinLangOptions.find { it.first == builtinLang }?.second ?: "",
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
                ExposedDropdownMenu(
                    expanded = builtinLangExpanded,
                    onDismissRequest = { builtinLangExpanded = false }
                ) {
                    builtinLangOptions.forEach { (code, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                builtinLang = code
                                prefs.edit().putString("builtin_lang", code).apply()
                                builtinLangExpanded = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SlateCard {
            Text(
                text = stringResource(R.string.settings_spinner_style_title),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            ExposedDropdownMenuBox(
                expanded = spinnerStyleExpanded,
                onExpandedChange = { spinnerStyleExpanded = !spinnerStyleExpanded }
            ) {
                OutlinedTextField(
                    value = spinnerStyleOptions.find { it.first == spinnerStyle }?.second ?: "",
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
                ExposedDropdownMenu(
                    expanded = spinnerStyleExpanded,
                    onDismissRequest = { spinnerStyleExpanded = false }
                ) {
                    spinnerStyleOptions.forEach { (code, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                spinnerStyle = code
                                prefs.edit().putString("spinner_style", code).apply()
                                spinnerStyleExpanded = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SectionHeader(stringResource(R.string.settings_trigger_prefix_title))
        SlateCard {
            Text(
                text = stringResource(R.string.settings_trigger_prefix_desc, triggerPrefix),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            SlateTextField(
                value = triggerPrefix,
                onValueChange = { input ->
                    val filtered = input.take(1)
                    triggerPrefix = filtered
                    prefixError = when {
                        filtered.length != 1 -> prefixErrorLength
                        filtered[0].isWhitespace() -> prefixErrorWhitespace
                        filtered[0].isLetterOrDigit() -> prefixErrorAlphanumeric
                        else -> {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            commandManager.setTriggerPrefix(filtered)
                            null
                        }
                    }
                },
                isError = prefixError != null,
                modifier = Modifier.width(80.dp)
            )
            prefixError?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SectionHeader(stringResource(R.string.backup_title))
        SlateCard {
            Text(
                text = stringResource(R.string.backup_desc),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        backupMessage = null
                        exportLauncher.launch("swiftslate-commands.json")
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(stringResource(R.string.backup_export))
                }
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        backupMessage = null
                        importLauncher.launch(arrayOf("application/json"))
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(stringResource(R.string.backup_import))
                }
            }
            backupMessage?.let { msg ->
                Text(
                    text = msg,
                    color = if (backupSuccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }


        Spacer(modifier = Modifier.height(12.dp))

        SlateCard {
            Text(
                text = stringResource(R.string.settings_blocklist_title),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_blocklist_desc),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    navController?.navigate("blocklist")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_blocklist_button))
            }
        }
    }
}
