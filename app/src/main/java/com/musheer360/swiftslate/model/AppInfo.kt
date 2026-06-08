package com.musheer360.swiftslate.model

import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

@Immutable
data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable
)

@Stable
class StablePrefs(val prefs: SharedPreferences)

