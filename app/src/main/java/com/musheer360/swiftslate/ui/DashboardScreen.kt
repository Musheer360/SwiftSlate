package com.musheer360.swiftslate.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.musheer360.swiftslate.BuildConfig
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.manager.CommandManager
import com.musheer360.swiftslate.manager.KeyManager
import com.musheer360.swiftslate.manager.StatsManager
import com.musheer360.swiftslate.ui.components.ScreenTitle
import com.musheer360.swiftslate.ui.components.SectionHeader
import com.musheer360.swiftslate.ui.components.SlateCard
import com.musheer360.swiftslate.ui.components.SlateDivider
import kotlinx.coroutines.delay

private fun checkServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
    return enabledServices.any {
        it.resolveInfo.serviceInfo.packageName == context.packageName
    }
}

@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val keyManager = remember { KeyManager(context) }
    val commandManager = remember { CommandManager(context) }
    val statsManager = remember { StatsManager(context) }

    var isServiceEnabled by remember { mutableStateOf(checkServiceEnabled(context)) }
    var keyCount by remember { mutableIntStateOf(keyManager.getKeys().size) }
    var currentPrefix by remember { mutableStateOf(commandManager.getTriggerPrefix()) }

    var usedTokens by remember { mutableLongStateOf(statsManager.getUsedTokens()) }
    var requestsThisMonth by remember { mutableIntStateOf(statsManager.getRequestsThisMonth()) }
    var favoriteCommand by remember { mutableStateOf(statsManager.getFavoriteCommand() ?: "") }
    var tokensChartData by remember { mutableStateOf(statsManager.getTokensLast7Days()) }

    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val newEnabled = checkServiceEnabled(context)
                val newKeyCount = keyManager.getKeys().size
                val newPrefix = commandManager.getTriggerPrefix()
                usedTokens = statsManager.getUsedTokens()
                requestsThisMonth = statsManager.getRequestsThisMonth()
                favoriteCommand = statsManager.getFavoriteCommand() ?: ""
                tokensChartData = statsManager.getTokensLast7Days()
                if (newEnabled != isServiceEnabled) isServiceEnabled = newEnabled
                if (newKeyCount != keyCount) keyCount = newKeyCount
                if (newPrefix != currentPrefix) currentPrefix = newPrefix
                delay(3000)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        ScreenTitle(stringResource(R.string.dashboard_title))

        SectionHeader(stringResource(R.string.service_status_title))
        SlateCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isServiceEnabled) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.error
                            )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isServiceEnabled) stringResource(R.string.service_status_active)
                        else stringResource(R.string.service_status_inactive),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (!isServiceEnabled) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            stringResource(R.string.service_enable),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            SlateDivider()
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.dashboard_api_keys_title),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.dashboard_keys_configured, keyCount),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (keyCount == 0) {
                Text(
                    text = stringResource(R.string.dashboard_add_key_hint),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SlateCard {
            Text(
                text = stringResource(R.string.dashboard_stats_title),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.dashboard_stats_tokens, usedTokens),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.dashboard_stats_requests, requestsThisMonth),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            val favDisplay = favoriteCommand.ifEmpty { stringResource(R.string.dashboard_stats_none) }
            Text(
                text = stringResource(R.string.dashboard_stats_favorite, favDisplay),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            TokensBarChart(tokensChartData)
        }

        Spacer(modifier = Modifier.height(20.dp))
        SectionHeader(stringResource(R.string.dashboard_how_to_use_title))

        SlateCard {
            Text(
                text = stringResource(R.string.dashboard_how_to_use_body, currentPrefix),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                lineHeight = 24.sp
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.dashboard_version, BuildConfig.VERSION_NAME),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.dashboard_github),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Musheer360/SwiftSlate"))
                    )
                }
                .padding(vertical = 8.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}
@Composable
fun TokensBarChart(data: List<Pair<String, Long>>) {
    val maxTokens = data.maxOfOrNull { it.second }?.coerceAtLeast(10L) ?: 10L

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        data.forEach { (dateStr, tokens) ->
            val heightFraction = if (tokens == 0L) {
                0f
            } else {
                (tokens.toFloat() / maxTokens.toFloat()).coerceIn(0.02f, 1f)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
                modifier = Modifier.weight(1f)
            ) {
                // Number of tokens rounded to K
                val displayTokens = if (tokens > 1000) "${tokens / 1000}k" else tokens.toString()
                if (tokens > 0) {
                    Text(
                        text = displayTokens,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                // The Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .fillMaxHeight(heightFraction * 0.7f) // leave space for labels
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                        )
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Date Label
                Text(
                    text = dateStr,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}