package com.musheer360.swiftslate

import android.app.Application
import android.content.Context

class SwiftSlateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Pre-warm SharedPreferences — triggers async disk load so they're
        // in memory by the time the ViewModel creates managers
        getSharedPreferences("settings", Context.MODE_PRIVATE)
        getSharedPreferences("commands", Context.MODE_PRIVATE)
        getSharedPreferences("secure_keys_prefs", Context.MODE_PRIVATE)
    }
}
