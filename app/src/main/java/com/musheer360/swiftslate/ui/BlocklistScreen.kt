package com.musheer360.swiftslate.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.widget.ImageView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.manager.BlocklistManager
import com.musheer360.swiftslate.model.AppInfo
import com.musheer360.swiftslate.ui.components.SlateCard
import com.musheer360.swiftslate.ui.components.SlateItemCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BlocklistScreen(prefs: SharedPreferences, onBack: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var blocklist by remember { mutableStateOf(BlocklistManager.getBlocklist(prefs)) }

    // Load apps asynchronously on launch
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    // Filter to only user-launchable apps to keep list clean and relevant
                    pm.getLaunchIntentForPackage(app.packageName) != null
                }
                .map { app ->
                    AppInfo(
                        name = app.loadLabel(pm).toString(),
                        packageName = app.packageName,
                        icon = app.loadIcon(pm)
                    )
                }
                .sortedBy { it.name.lowercase() }
            
            withContext(Dispatchers.Main) {
                installedApps = apps
                isLoading = false
            }
        }
    }

    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { }
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Top row with ArrowBack and Title
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onBack()
                },
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.commands_cancel),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = stringResource(R.string.settings_blocklist_title),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Subtitle Description
        Text(
            text = stringResource(R.string.settings_blocklist_desc),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Search Pill
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        Box {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.blocklist_search_placeholder),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.commands_search_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable(interactionSource = null, indication = null) {
                                searchQuery = ""
                            }
                    )
                }
            }
        }

        // App List
        SlateCard(
            modifier = Modifier.weight(1f),
            fillHeight = true
        ) {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.blocklist_empty),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val isBlocked = blocklist.contains(app.packageName)
                        SlateItemCard {
                            // Native image icon for flawless rendering
                            AndroidView(
                                factory = { ctx ->
                                    ImageView(ctx).apply {
                                        scaleType = ImageView.ScaleType.FIT_CENTER
                                    }
                                },
                                modifier = Modifier.size(40.dp),
                                update = { imageView ->
                                    imageView.setImageDrawable(app.icon)
                                }
                            )
                            
                            Spacer(modifier = Modifier.width(12.dp))
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = app.packageName,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            Switch(
                                checked = isBlocked,
                                onCheckedChange = { checkState ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (checkState) {
                                        BlocklistManager.addApp(prefs, app.packageName)
                                    } else {
                                        BlocklistManager.removeApp(prefs, app.packageName)
                                    }
                                    blocklist = BlocklistManager.getBlocklist(prefs)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                    uncheckedTrackColor = MaterialTheme.colorScheme.surface
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
