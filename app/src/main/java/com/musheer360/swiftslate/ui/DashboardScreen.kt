package com.musheer360.swiftslate.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.manager.CommandManager
import com.musheer360.swiftslate.manager.KeyManager
import com.musheer360.swiftslate.ui.components.ScreenTitle
import com.musheer360.swiftslate.ui.components.SlateCard
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
    var isServiceEnabled by remember { mutableStateOf(checkServiceEnabled(context)) }
    var keyCount by remember { mutableIntStateOf(keyManager.getKeys().size) }
    var currentPrefix by remember { mutableStateOf(commandManager.getTriggerPrefix()) }

    // Use the Activity lifecycle so polling only restarts when the app returns
    // from the background, not when switching between navbar tabs.
    val activityLifecycle = (context as? ComponentActivity)?.lifecycle

    LaunchedEffect(activityLifecycle) {
        val lifecycle = activityLifecycle ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                isServiceEnabled = checkServiceEnabled(context)
                keyCount = keyManager.getKeys().size
                currentPrefix = commandManager.getTriggerPrefix()
                delay(3000)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        ScreenTitle(stringResource(R.string.dashboard_title))

        SlateCard {
            Text(
                text = stringResource(R.string.service_status_title),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isServiceEnabled) stringResource(R.string.service_status_active) else stringResource(R.string.service_status_inactive),
                    color = if (isServiceEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
                if (!isServiceEnabled) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Text(stringResource(R.string.service_enable), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SlateCard {
            Text(
                text = stringResource(R.string.dashboard_api_keys_title),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.dashboard_keys_configured, keyCount),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp
            )
            if (keyCount == 0) {
                Text(
                    text = stringResource(R.string.dashboard_add_key_hint),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SlateCard {
            Text(
                text = stringResource(R.string.dashboard_how_to_use_title),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.dashboard_how_to_use_body, currentPrefix),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp,
                lineHeight = 22.sp
            )
        }
    }
}
