package com.musheer360.swiftslate

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import com.musheer360.swiftslate.manager.CommandManager
import com.musheer360.swiftslate.manager.KeyManager

class SwiftSlateViewModel(application: Application) : AndroidViewModel(application) {
    val prefs: SharedPreferences = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val keyManager = KeyManager(application)
    val commandManager = CommandManager(application)
}
