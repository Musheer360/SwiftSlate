package com.musheer360.swiftslate.ui

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
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
import androidx.core.content.FileProvider
import com.musheer360.swiftslate.BuildConfig
import com.musheer360.swiftslate.ui.components.ScreenTitle
import com.musheer360.swiftslate.ui.components.SlateCard
import com.musheer360.swiftslate.update.UpdateChecker
import com.musheer360.swiftslate.update.UpdateInfo
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var providerType by remember { mutableStateOf(prefs.getString("provider_type", "gemini") ?: "gemini") }
    var providerExpanded by remember { mutableStateOf(false) }

    // Gemini settings
    var selectedModel by remember { mutableStateOf(prefs.getString("model", "gemini-2.5-flash-lite") ?: "gemini-2.5-flash-lite") }
    var modelExpanded by remember { mutableStateOf(false) }
    val geminiModels = listOf("gemini-2.5-flash-lite", "gemini-3-flash-preview")

    // Custom provider settings
    var customEndpoint by remember { mutableStateOf(prefs.getString("custom_endpoint", "") ?: "") }
    var customModel by remember { mutableStateOf(prefs.getString("custom_model", "") ?: "") }

    // Update state
    var updateInfo by remember { mutableStateOf(UpdateChecker.getCachedUpdate(context)) }
    var isChecking by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        ScreenTitle("Settings")

        // Update card
        UpdateCard(
            context = context,
            updateInfo = updateInfo,
            isChecking = isChecking,
            isDownloading = isDownloading,
            onCheckForUpdate = {
                scope.launch {
                    isChecking = true
                    val current = BuildConfig.VERSION_NAME
                    val result = UpdateChecker.checkForUpdate(current)
                    if (result != null) {
                        UpdateChecker.cacheUpdate(context, result)
                        updateInfo = result
                    } else {
                        UpdateChecker.clearCache(context)
                        updateInfo = null
                    }
                    isChecking = false
                }
            },
            onDownloadUpdate = { info ->
                isDownloading = true
                downloadAndInstall(context, info) {
                    isDownloading = false
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        SlateCard {
            Text(
                text = "Provider",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            ExposedDropdownMenuBox(
                expanded = providerExpanded,
                onExpandedChange = { providerExpanded = !providerExpanded }
            ) {
                OutlinedTextField(
                    value = if (providerType == "gemini") "Google Gemini" else "Custom (OpenAI Compatible)",
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
                ExposedDropdownMenu(
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Google Gemini") },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            providerType = "gemini"
                            prefs.edit().putString("provider_type", "gemini").apply()
                            providerExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Custom (OpenAI Compatible)") },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            providerType = "custom"
                            prefs.edit().putString("provider_type", "custom").apply()
                            providerExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (providerType == "gemini") {
            SlateCard {
                Text(
                    text = "Model",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))

                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = !modelExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedModel,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
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
                                    prefs.edit().putString("model", model).apply()
                                    modelExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        } else {
            SlateCard {
                Text(
                    text = "Endpoint",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Base URL of the OpenAI-compatible API",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = customEndpoint,
                    onValueChange = {
                        customEndpoint = it
                        prefs.edit().putString("custom_endpoint", it).apply()
                    },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            SlateCard {
                Text(
                    text = "Model",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Model identifier from your provider",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = customModel,
                    onValueChange = {
                        customModel = it
                        prefs.edit().putString("custom_model", it).apply()
                    },
                    placeholder = { Text("gpt-4o, claude-3-haiku, etc.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // App version
        SlateCard {
            Text(
                text = "About",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "SwiftSlate v${BuildConfig.VERSION_NAME}",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UpdateCard(
    context: Context,
    updateInfo: UpdateInfo?,
    isChecking: Boolean,
    isDownloading: Boolean,
    onCheckForUpdate: () -> Unit,
    onDownloadUpdate: (UpdateInfo) -> Unit
) {
    val haptic = LocalHapticFeedback.current

    SlateCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = if (updateInfo != null) MaterialTheme.colorScheme.tertiary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (updateInfo != null) "Update Available"
                           else "App is up to date",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (updateInfo != null) {
                    Text(
                        text = "v${updateInfo.version}",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (updateInfo != null) {
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDownloadUpdate(updateInfo)
                },
                enabled = !isDownloading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (isDownloading) "Downloading…" else "Update Now",
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        } else {
            OutlinedButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCheckForUpdate()
                },
                enabled = !isChecking,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isChecking) "Checking…" else "Check for Updates"
                )
            }
        }
    }
}

private fun downloadAndInstall(context: Context, info: UpdateInfo, onComplete: () -> Unit) {
    val fileName = "SwiftSlate-v${info.version}.apk"

    // Remove old APK if it exists
    val destFile = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        fileName
    )
    if (destFile.exists()) destFile.delete()

    val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
        .setTitle("SwiftSlate v${info.version}")
        .setDescription("Downloading update…")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val downloadId = dm.enqueue(request)

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != downloadId) return
            context.unregisterReceiver(this)
            onComplete()
            installApk(context, fileName)
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_EXPORTED
        )
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        )
    }

    Toast.makeText(context, "Downloading update…", Toast.LENGTH_SHORT).show()
}

private fun installApk(context: Context, fileName: String) {
    val file = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        fileName
    )
    if (!file.exists()) return

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
    context.startActivity(intent)
}
