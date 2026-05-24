package com.musheer360.swiftslate

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import com.musheer360.swiftslate.manager.CommandManager
import com.musheer360.swiftslate.manager.KeyManager
import com.musheer360.swiftslate.manager.StatsManager
import com.musheer360.swiftslate.model.StablePrefs

class SwiftSlateViewModel(application: Application) : AndroidViewModel(application) {
    private val rawPrefs: SharedPreferences = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val prefs = StablePrefs(rawPrefs)
    val keyManager = KeyManager(application)
    val commandManager = CommandManager(application)
    val statsManager = StatsManager(application)
}
