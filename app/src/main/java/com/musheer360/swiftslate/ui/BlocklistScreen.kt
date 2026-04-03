package com.musheer360.swiftslate.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavController
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.manager.BlocklistManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppItem(
    val packageName: String,
    val appName: String,
    val icon: android.graphics.drawable.Drawable
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlocklistScreen(navController: NavController) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val blocklistManager = remember { BlocklistManager(context) }

    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var blockedPackages by remember { mutableStateOf(blocklistManager.getBlockedPackages()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfoList = pm.queryIntentActivities(intent, 0)

            val loadedApps = resolveInfoList.mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo.packageName
                if (packageName == context.packageName) return@mapNotNull null

                try {
                    val appName = resolveInfo.loadLabel(pm).toString()
                    val icon = resolveInfo.loadIcon(pm)
                    AppItem(packageName, appName, icon)
                } catch (e: Exception) {
                    null
                }
            }.sortedBy { it.appName.lowercase() }

            apps = loadedApps
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.blocklist_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            var searchQuery by remember { mutableStateOf("") }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search apps...") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                val filteredApps =
                    apps.filter { it.appName.contains(searchQuery, ignoreCase = true) }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val isBlocked = blockedPackages.contains(app.packageName)

                        ListItem(
                            headlineContent = { Text(app.appName, fontSize = 16.sp) },
                            supportingContent = {
                                Text(
                                    app.packageName,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            leadingContent = {
                                val bitmap = remember(app.packageName) {
                                    try {
                                        app.icon.toBitmap().asImageBitmap()
                                    } catch (e: Exception) {
                                        val fallback = android.graphics.Bitmap.createBitmap(
                                            1,
                                            1,
                                            android.graphics.Bitmap.Config.ARGB_8888
                                        )
                                        fallback.asImageBitmap()
                                    }
                                }
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = app.appName,
                                    modifier = Modifier.size(40.dp)
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = isBlocked,
                                    onCheckedChange = { checked ->
                                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                        blocklistManager.setBlocked(app.packageName, checked)
                                        blockedPackages = blocklistManager.getBlockedPackages()
                                    }
                                )
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
