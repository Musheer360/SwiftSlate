package com.musheer360.swiftslate.model

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable

@Immutable
data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable
)
