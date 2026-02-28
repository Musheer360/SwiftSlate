package com.musheer360.typeslate.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musheer360.typeslate.api.GeminiClient
import com.musheer360.typeslate.manager.KeyManager
import com.musheer360.typeslate.ui.components.ScreenTitle
import com.musheer360.typeslate.ui.components.SlateCard
import kotlinx.coroutines.launch

@Composable
fun KeysScreen() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val keyManager = remember { KeyManager(context) }
    var keys by remember { mutableStateOf(keyManager.getKeys()) }
    var newKey by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val client = remember { GeminiClient() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 24.dp)
    ) {
        ScreenTitle("API Keys")

        SlateCard {
            OutlinedTextField(
                value = newKey,
                onValueChange = { newKey = it },
                label = { Text("Gemini API Key") },
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
                                if (keys.contains(trimmedKey)) {
                                    isTesting = false
                                    testResult = "This key has already been added"
                                    return@launch
                                }
                                val result = client.validateKey(trimmedKey)
                                isTesting = false
                                if (result.isSuccess) {
                                    keyManager.addKey(trimmedKey)
                                    keys = keyManager.getKeys()
                                    newKey = ""
                                    testResult = "Valid key added!"
                                } else {
                                    testResult = result.exceptionOrNull()?.message ?: "Validation failed"
                                }
                            }
                        }
                    },
                    enabled = newKey.isNotBlank() && !isTesting
                ) {
                    Text(if (isTesting) "Testing..." else "Add Key")
                }
            }
            if (testResult != null) {
                Text(
                    text = testResult!!,
                    color = if (testResult!!.startsWith("Valid")) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(keys) { key ->
                SlateCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                                contentDescription = "Delete Key",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}