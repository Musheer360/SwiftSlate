package com.musheer360.swiftslate.manager

import android.content.SharedPreferences

object BlocklistManager {
    private const val KEY = "blocked_packages"

    fun getBlocklist(prefs: SharedPreferences): Set<String> {
        return prefs.getStringSet(KEY, emptySet()) ?: emptySet()
    }

    fun addApp(prefs: SharedPreferences, packageName: String) {
        val current = getBlocklist(prefs).toMutableSet()
        current.add(packageName)
        prefs.edit().putStringSet(KEY, current).apply()
    }

    fun removeApp(prefs: SharedPreferences, packageName: String) {
        val current = getBlocklist(prefs).toMutableSet()
        current.remove(packageName)
        prefs.edit().putStringSet(KEY, current).apply()
    }

    fun isBlocked(prefs: SharedPreferences, packageName: String): Boolean {
        return packageName in getBlocklist(prefs)
    }
}
