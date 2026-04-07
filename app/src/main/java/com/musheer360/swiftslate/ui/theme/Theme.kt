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
    surface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF1C1C1E),
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF1A1A1A),
    onPrimaryContainer = Color(0xFFFFFFFF),
    error = Color(0xFFFF453A),
    tertiary = Color(0xFF30D158),
    tertiaryContainer = Color(0xFFFFD60A)
)

private val LightColorScheme = lightColorScheme(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFFEEEEEE),
    surfaceContainerHigh = Color(0xFFE8E8E8),
    onBackground = Color(0xFF000000),
    onSurface = Color(0xFF000000),
    onSurfaceVariant = Color(0xFF6E6E73),
    outline = Color(0xFFE5E5EA),
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF0F0F0),
    onPrimaryContainer = Color(0xFF000000),
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
