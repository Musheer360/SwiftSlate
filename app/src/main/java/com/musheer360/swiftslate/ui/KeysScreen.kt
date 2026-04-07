package com.musheer360.swiftslate.ui

import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.api.GeminiClient
import com.musheer360.swiftslate.api.OpenAICompatibleClient
import com.musheer360.swiftslate.manager.KeyManager
import com.musheer360.swiftslate.ui.components.ScreenTitle
import com.musheer360.swiftslate.ui.components.SlateCard
import kotlinx.coroutines.launch

@Composable
fun KeysScreen() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val uriHandler = LocalUriHandler.current
    val keyManager = remember { KeyManager(context) }
    var keys by remember { mutableStateOf(keyManager.getKeys()) }
    var newKey by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val geminiClient = remember { GeminiClient() }
    val openAIClient = remember { OpenAICompatibleClient() }
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    val validAddedMsg = stringResource(R.string.keys_valid_added)
    val alreadyAddedMsg = stringResource(R.string.keys_already_added)
    val validationFailedMsg = stringResource(R.string.keys_validation_failed)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 24.dp)
    ) {
        ScreenTitle(stringResource(R.string.keys_title))

        SlateCard {
            OutlinedTextField(
                value = newKey,
                onValueChange = { newKey = it },
                label = { Text(stringResource(R.string.keys_api_key_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = {
                        if (newKey.isNotBlank()) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            isTesting = true
                            testResult = null
                            scope.launch {
                                val trimmedKey = newKey.trim()
                                if (keyManager.getKeys().contains(trimmedKey)) {
                                    isTesting = false
                                    testResult = alreadyAddedMsg
                                    testSuccess = false
                                    return@launch
                                }
                                val result = run {
                                    val providerType = prefs.getString("provider_type", "gemini") ?: "gemini"
                                    val customEndpoint = prefs.getString("custom_endpoint", "") ?: ""
                                    when {
                                        providerType == "groq" ->
                                            openAIClient.validateKey(trimmedKey, "https://api.groq.com/openai/v1")
                                        providerType == "custom" && customEndpoint.isNotBlank() ->
                                            openAIClient.validateKey(trimmedKey, customEndpoint)
                                        else ->
                                            geminiClient.validateKey(trimmedKey)
                                    }
                                }
                                isTesting = false
                                if (result.isSuccess) {
                                    keyManager.addKey(trimmedKey)
                                    keys = keyManager.getKeys()
                                    newKey = ""
                                    testResult = validAddedMsg
                                    testSuccess = true
                                } else {
                                    testResult = result.exceptionOrNull()?.message ?: validationFailedMsg
                                    testSuccess = false
                                }
                            }
                        }
                    },
                    enabled = newKey.isNotBlank() && !isTesting
                ) {
                    Text(if (isTesting) stringResource(R.string.keys_testing) else stringResource(R.string.keys_add_key))
                }
            }
            if (testResult != null) {
                Text(
                    text = testResult!!,
                    color = if (testSuccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            val (apiKeyUrl, providerName) = when (prefs.getString("provider_type", "gemini") ?: "gemini") {
                "groq" -> "https://console.groq.com/keys" to "Groq"
                "custom" -> null to null
                else -> "https://aistudio.google.com/api-keys" to "Gemini"
            }
            if (apiKeyUrl != null && providerName != null) {
                Text(
                    text = stringResource(R.string.keys_get_api_key, providerName),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clickable { uriHandler.openUri(apiKeyUrl) }
                        .padding(vertical = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(keys, key = { it }) { key ->
                SlateCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "••••••••" + key.takeLast(6),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            keyManager.removeKey(key)
                            keys = keyManager.getKeys()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.keys_delete_key),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}