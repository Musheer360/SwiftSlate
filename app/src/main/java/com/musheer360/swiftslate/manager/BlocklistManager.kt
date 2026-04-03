package com.musheer360.swiftslate.manager

import android.content.Context
import android.content.SharedPreferences

class BlocklistManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("blocklist", Context.MODE_PRIVATE)

    companion object {
        private const val PREF_BLOCKED_APPS = "blocked_apps"
    }

    fun getBlockedPackages(): Set<String> {
        return prefs.getStringSet(PREF_BLOCKED_APPS, emptySet()) ?: emptySet()
    }

    fun isBlocked(packageName: String): Boolean {
        return getBlockedPackages().contains(packageName)
    }

    fun setBlocked(packageName: String, blocked: Boolean) {
        val current = getBlockedPackages().toMutableSet()
        if (blocked) {
            current.add(packageName)
        } else {
            current.remove(packageName)
        }
        prefs.edit().putStringSet(PREF_BLOCKED_APPS, current).apply()
    }
}
