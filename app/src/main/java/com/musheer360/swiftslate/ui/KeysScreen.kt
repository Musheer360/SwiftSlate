package com.musheer360.swiftslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.api.GeminiClient
import com.musheer360.swiftslate.api.OpenAICompatibleClient
import com.musheer360.swiftslate.manager.KeyManager
import com.musheer360.swiftslate.model.ProviderType
import com.musheer360.swiftslate.ui.components.ScreenTitle
import com.musheer360.swiftslate.ui.components.SlateCard
import com.musheer360.swiftslate.ui.components.SlateItemCard
import com.musheer360.swiftslate.ui.components.SlateTextField
import kotlinx.coroutines.launch

@Composable
fun KeysScreen(keyManager: KeyManager, prefs: SharedPreferences) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val uriHandler = LocalUriHandler.current
    var keys by remember { mutableStateOf(keyManager.getKeys()) }
    var keyToDelete by remember { mutableStateOf<String?>(null) }
    var newKey by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val geminiClient = remember { GeminiClient() }
    val openAIClient = remember { OpenAICompatibleClient() }

    val validAddedMsg = stringResource(R.string.keys_valid_added)
    val alreadyAddedMsg = stringResource(R.string.keys_already_added)
    val validationFailedMsg = stringResource(R.string.keys_validation_failed)
    val keystoreErrorMsg = stringResource(R.string.keys_keystore_error)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { } // Creates a hardware layer for smooth NavHost slide animations
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        ScreenTitle(stringResource(R.string.keys_title))

        if (!keyManager.keystoreAvailable) {
            SlateCard {
                Text(
                    text = keystoreErrorMsg,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        SlateCard {
            SlateTextField(
                value = newKey,
                onValueChange = { if (it.length <= 256) newKey = it },
                placeholder = { Text(stringResource(R.string.keys_api_key_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(modifier = Modifier.height(12.dp))
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
                                val providerType = prefs.getString("provider_type", ProviderType.GEMINI) ?: ProviderType.GEMINI
                                val customEndpoint = prefs.getString("custom_endpoint", "") ?: ""
                                when {
                                    providerType == ProviderType.GROQ ->
                                        openAIClient.validateKey(trimmedKey, "https://api.groq.com/openai/v1")
                                    providerType == ProviderType.CUSTOM && customEndpoint.isNotBlank() ->
                                        openAIClient.validateKey(trimmedKey, customEndpoint)
                                    else ->
                                        geminiClient.validateKey(trimmedKey)
                                }
                            }
                            isTesting = false
                            if (result.isSuccess) {
                                if (!keyManager.addKey(trimmedKey)) {
                                    testResult = keystoreErrorMsg
                                    testSuccess = false
                                    return@launch
                                }
                                keys = keyManager.getKeys()
                                newKey = ""
                                testResult = validAddedMsg
                                testSuccess = true
                                // Clear clipboard to prevent API key leaking via paste history
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                            } else {
                                testResult = result.exceptionOrNull()?.message ?: validationFailedMsg
                                testSuccess = false
                            }
                        }
                    }
                },
                enabled = newKey.isNotBlank() && !isTesting && keyManager.keystoreAvailable,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(if (isTesting) stringResource(R.string.keys_testing) else stringResource(R.string.keys_add_key))
            }
            if (testResult != null) {
                Text(
                    text = testResult!!,
                    color = if (testSuccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            val (apiKeyUrl, providerName) = when (prefs.getString("provider_type", ProviderType.GEMINI) ?: ProviderType.GEMINI) {
                ProviderType.GROQ -> "https://console.groq.com/keys" to "Groq"
                ProviderType.CUSTOM -> null to null
                else -> "https://aistudio.google.com/api-keys" to "Gemini"
            }
            if (apiKeyUrl != null && providerName != null) {
                Text(
                    text = stringResource(R.string.keys_get_api_key, providerName),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable(interactionSource = null, indication = null) { uriHandler.openUri(apiKeyUrl) }
                        .padding(top = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (keys.isNotEmpty()) {
            SlateCard(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    itemsIndexed(keys, key = { index, k -> "$index-${k.hashCode()}" }) { index, key ->
                        SlateItemCard {
                            Text(
                                text = "••••••••" + key.takeLast(4),
                                fontWeight = FontWeight.Medium,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}
                            )
                            Text(
                                text = stringResource(R.string.delete_confirm_button),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable(
                                    interactionSource = null,
                                    indication = null
                                ) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    keyToDelete = key
                                }
                            )
                        }
                    }
                }
            }
        } else {
            SlateCard(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.keys_empty),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    keyToDelete?.let { keyValue ->
        AlertDialog(
            onDismissRequest = { keyToDelete = null },
            title = { Text(stringResource(R.string.delete_confirm_key_title)) },
            text = { Text(stringResource(R.string.delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (keyManager.removeKey(keyValue)) {
                        keys = keyManager.getKeys()
                    } else {
                        testResult = keystoreErrorMsg
                        testSuccess = false
                    }
                    keyToDelete = null
                }) {
                    Text(stringResource(R.string.delete_confirm_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { keyToDelete = null }) {
                    Text(stringResource(R.string.commands_cancel))
                }
            }
        )
    }
}