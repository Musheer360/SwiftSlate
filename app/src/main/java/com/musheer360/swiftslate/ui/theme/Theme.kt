package com.musheer360.swiftslate.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    background = Color(0xFF000000),
    surface = Color(0xFF0D0D0D),
    surfaceVariant = Color(0xFF1A1A1A),
    surfaceContainerHigh = Color(0xFF222222),
    onBackground = Color(0xFFE0E2E6),
    onSurface = Color(0xFFE0E2E6),
    onSurfaceVariant = Color(0xFF8A8A90),
    outline = Color(0xFF2A2A2E),
    primary = Color(0xFFE0E2E6),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF1A1A1A),
    onPrimaryContainer = Color(0xFFE0E2E6),
    error = Color(0xFFFF453A),
    tertiary = Color(0xFF30D158),
    tertiaryContainer = Color(0xFFFFD60A)
)

private val LightColorScheme = lightColorScheme(
    background = Color(0xFFFCFCFC),
    surface = Color(0xFFF2F2F4),
    surfaceVariant = Color(0xFFE8E8EA),
    surfaceContainerHigh = Color(0xFFDFDFE1),
    onBackground = Color(0xFF1A1A1E),
    onSurface = Color(0xFF1A1A1E),
    onSurfaceVariant = Color(0xFF6E6E73),
    outline = Color(0xFFD5D5DA),
    primary = Color(0xFF1A1A1E),
    onPrimary = Color(0xFFFCFCFC),
    primaryContainer = Color(0xFFEEEEF0),
    onPrimaryContainer = Color(0xFF1A1A1E),
    error = Color(0xFFFF3B30),
    tertiary = Color(0xFF34C759),
    tertiaryContainer = Color(0xFFFF9500)
)

@Composable
fun SwiftSlateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
