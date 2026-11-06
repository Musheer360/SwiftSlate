package com.musheer360.swiftslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.api.ApiClientUtils
import com.musheer360.swiftslate.api.GeminiClient
import com.musheer360.swiftslate.api.OpenAICompatibleClient
import com.musheer360.swiftslate.manager.KeyManager
import com.musheer360.swiftslate.model.PrefKeys
import com.musheer360.swiftslate.model.ProviderType
import com.musheer360.swiftslate.provider.CommandCodeConfig
import com.musheer360.swiftslate.provider.DeepSeekConfig
import com.musheer360.swiftslate.provider.GroqConfig
import com.musheer360.swiftslate.provider.OpenCodeGoConfig
import com.musheer360.swiftslate.ui.components.LocalSlateRhythm
import com.musheer360.swiftslate.ui.components.SlateCard
import com.musheer360.swiftslate.ui.components.SlateItemCard
import com.musheer360.swiftslate.ui.components.SlateTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * API-key management for the active provider, embedded in Settings.
 *
 * Keys are per provider (see [KeyManager]), so this reloads whenever [provider] changes and
 * reports every add/delete through [onKeysChanged] — Settings uses that to refresh the key
 * count that gates its model picker.
 */
@Composable
fun ProviderKeysCard(
    keyManager: KeyManager,
    prefs: SharedPreferences,
    provider: String,
    onKeysChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val uriHandler = LocalUriHandler.current
    // Deliberately not seeded from keyManager.getKeys(): that decrypts through AndroidKeyStore
    // (and on a legacy store also does a synchronous prefs commit), which would run on the main
    // thread during composition. Loaded in the LaunchedEffect below instead.
    var keys by remember { mutableStateOf<List<String>>(emptyList()) }
    var keyToDelete by remember { mutableStateOf<String?>(null) }
    var newKey by rememberSaveable { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val geminiClient = remember { GeminiClient() }
    val openAIClient = remember { OpenAICompatibleClient() }
    val rhythm = LocalSlateRhythm.current

    LaunchedEffect(provider) {
        testResult = null
        testSuccess = false
        // Blank the list first: the new provider's keys load off the main thread, and showing
        // the previous provider's masked keys in the meantime invites deleting the wrong entry.
        keys = emptyList()
        keys = withContext(Dispatchers.IO) { keyManager.getKeys(provider) }
    }

    val providerLabel = when (provider) {
        ProviderType.GROQ -> "Groq"
        ProviderType.COMMAND_CODE -> "Command Code"
        ProviderType.OPENCODE_GO -> "OpenCode Go"
        ProviderType.DEEPSEEK -> "DeepSeek"
        ProviderType.CUSTOM -> stringResource(R.string.settings_provider_custom)
        else -> stringResource(R.string.settings_provider_gemini)
    }

    val validAddedMsg = stringResource(R.string.keys_valid_added)
    val alreadyAddedMsg = stringResource(R.string.keys_already_added)
    val validationFailedMsg = stringResource(R.string.keys_validation_failed)
    val keystoreErrorMsg = stringResource(R.string.keys_keystore_error)
    val customEndpointRequiredMsg = stringResource(R.string.keys_custom_endpoint_required)
    val signinRequiredMsg = stringResource(R.string.error_provider_auth_required)
    val planNoApiAccessMsg = stringResource(R.string.error_plan_no_api_access)
    val endpointNeedsV1Msg = stringResource(R.string.keys_endpoint_needs_v1)
    val providerChangedMsg = stringResource(R.string.keys_provider_changed)

    fun reloadKeys() {
        scope.launch {
            keys = withContext(Dispatchers.IO) { keyManager.getKeys(provider) }
        }
    }

    SlateCard {
        if (!keyManager.keystoreAvailable) {
            Text(
                text = keystoreErrorMsg,
                color = MaterialTheme.colorScheme.error,
                fontSize = rhythm.bodySize
            )
            Spacer(modifier = Modifier.height(rhythm.formGap))
        }

        Text(
            text = "${stringResource(R.string.keys_title)} · $providerLabel",
            fontSize = rhythm.bodySize,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(rhythm.formGap))
        SlateTextField(
            value = newKey,
            onValueChange = { if (it.length <= 256) newKey = it },
            placeholder = { Text(stringResource(R.string.keys_api_key_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        Spacer(modifier = Modifier.height(rhythm.groupGap))
        Button(
            onClick = {
                if (newKey.isNotBlank()) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    isTesting = true
                    testResult = null
                    scope.launch {
                        val trimmedKey = newKey.trim()
                        if (withContext(Dispatchers.IO) { keyManager.getKeys(provider) }.contains(trimmedKey)) {
                            isTesting = false
                            // Re-adding an existing key means the user is retrying it after a
                            // failure — clear any invalid/rate-limit bench so the service can
                            // use it again immediately instead of waiting out the 15-min TTL.
                            withContext(Dispatchers.IO) { keyManager.clearMarks(trimmedKey) }
                            testResult = alreadyAddedMsg
                            testSuccess = false
                            return@launch
                        }
                        val result = run {
                            val providerType = provider
                            val customEndpoint = (prefs.getString(PrefKeys.CUSTOM_ENDPOINT, "") ?: "").trim()
                            when {
                                providerType == ProviderType.CUSTOM && customEndpoint.isBlank() -> {
                                    isTesting = false
                                    testResult = customEndpointRequiredMsg
                                    testSuccess = false
                                    return@launch
                                }
                                providerType == ProviderType.GROQ ->
                                    openAIClient.validateKey(trimmedKey, GroqConfig.ENDPOINT)
                                // Both catalogs are served without auth, so a GET proves
                                // nothing; probe with one authenticated token instead.
                                providerType == ProviderType.COMMAND_CODE ->
                                    openAIClient.validateKeyWithProbe(
                                        trimmedKey, CommandCodeConfig.ENDPOINT, CommandCodeConfig.defaultModel)
                                providerType == ProviderType.OPENCODE_GO ->
                                    openAIClient.validateKeyWithProbe(
                                        trimmedKey, OpenCodeGoConfig.ENDPOINT, OpenCodeGoConfig.defaultModel)
                                providerType == ProviderType.DEEPSEEK ->
                                    openAIClient.validateKey(trimmedKey, DeepSeekConfig.ENDPOINT)
                                providerType == ProviderType.CUSTOM ->
                                    openAIClient.validateKey(trimmedKey, customEndpoint)
                                else ->
                                    geminiClient.validateKey(trimmedKey)
                            }
                        }
                        isTesting = false
                        if (result.isSuccess) {
                            // The provider may have changed while the request was in flight; the
                            // key was validated against the old endpoint, so never file it under
                            // the new provider's pool.
                            if (provider != ProviderType.sanitize(prefs.getString(PrefKeys.PROVIDER_TYPE, null))) {
                                testResult = providerChangedMsg
                                testSuccess = false
                                return@launch
                            }
                            if (!withContext(Dispatchers.IO) { keyManager.addKey(trimmedKey, provider) }) {
                                testResult = keystoreErrorMsg
                                testSuccess = false
                                return@launch
                            }
                            keys = withContext(Dispatchers.IO) { keyManager.getKeys(provider) }
                            newKey = ""
                            testResult = validAddedMsg
                            testSuccess = true
                            onKeysChanged()
                            // Clear clipboard to prevent API key leaking via paste history
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                        } else {
                            // redactSecrets: some OpenAI-compatible endpoints echo the submitted
                            // key back in error.message. This is the one path that shows a raw
                            // provider message — the accessibility service maps every message
                            // onto a localized string instead — so it has to strip secrets.
                            val raw = result.exceptionOrNull()?.message ?: ""
                            testResult = when {
                                raw.contains(ApiClientUtils.SIGNIN_REQUIRED_MARKER) -> signinRequiredMsg
                                raw.contains("upgrade_required", ignoreCase = true) -> planNoApiAccessMsg
                                raw.contains(ApiClientUtils.NEEDS_V1_MARKER) -> endpointNeedsV1Msg
                                else -> ApiClientUtils.redactSecrets(raw).ifEmpty { validationFailedMsg }
                            }
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
                fontSize = rhythm.bodySize,
                modifier = Modifier.padding(top = rhythm.formGap)
            )
        }
        val apiKeyUrl = when (provider) {
            ProviderType.GROQ -> "https://console.groq.com/keys"
            ProviderType.COMMAND_CODE -> "https://commandcode.ai/studio/"
            ProviderType.OPENCODE_GO -> "https://opencode.ai/auth"
            ProviderType.DEEPSEEK -> "https://platform.deepseek.com/api_keys"
            ProviderType.CUSTOM -> null
            else -> "https://aistudio.google.com/api-keys"
        }
        if (apiKeyUrl != null) {
            Text(
                text = stringResource(R.string.keys_get_api_key, providerLabel),
                color = MaterialTheme.colorScheme.primary,
                fontSize = rhythm.bodySize,
                // Generous vertical padding: this is the only way to reach the provider's key
                // page, and a bare text node is a ~17 dp touch target.
                modifier = Modifier
                    .clickable(interactionSource = null, indication = null) { uriHandler.openUri(apiKeyUrl) }
                    .padding(vertical = 12.dp)
            )
        }

        if (keys.isNotEmpty()) {
            Spacer(modifier = Modifier.height(rhythm.groupGap))
            // Plain Column, not LazyColumn: a nested scrollable would swallow drags aimed at
            // the page now that the whole Settings screen scrolls, and key lists are short.
            Column(verticalArrangement = Arrangement.spacedBy(rhythm.listGap)) {
                keys.forEach { key ->
                    SlateItemCard {
                        Text(
                            text = "••••••••" + key.takeLast(4),
                            fontWeight = FontWeight.Medium,
                            fontSize = rhythm.emphasisSize,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}
                        )
                        Text(
                            text = stringResource(R.string.delete_confirm_button),
                            fontSize = rhythm.bodySize,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                // Destructive action: keep a full-height touch target.
                                .clickable(interactionSource = null, indication = null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    keyToDelete = key
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                        )
                    }
                }
            }
        } else {
            Text(
                text = stringResource(R.string.keys_empty),
                fontSize = rhythm.bodySize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = rhythm.groupGap)
            )
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
                    keyToDelete = null
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) { keyManager.removeKey(keyValue, provider) }
                        if (removed) {
                            reloadKeys()
                            onKeysChanged()
                        } else {
                            testResult = keystoreErrorMsg
                            testSuccess = false
                        }
                    }
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
