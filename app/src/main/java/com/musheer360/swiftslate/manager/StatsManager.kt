package com.musheer360.swiftslate.manager

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("stats", Context.MODE_PRIVATE)

    companion object {
        private const val PREF_TOTAL_TOKENS = "total_tokens"
        private const val PREF_CURRENT_MONTH = "current_month"
        private const val PREF_REQUESTS_THIS_MONTH = "requests_this_month"
        private const val CMD_PREFIX = "cmd_"
        private const val DAILY_PREFIX = "tokens_"
    }

    private fun getCurrentMonthString(): String {
        return SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
    }

    private fun getCurrentDayString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    fun addTokens(count: Int) {
        val current = prefs.getLong(PREF_TOTAL_TOKENS, 0L)
        val today = getCurrentDayString()
        val dailyPref = "$DAILY_PREFIX$today"
        val currentDaily = prefs.getLong(dailyPref, 0L)

        prefs.edit()
            .putLong(PREF_TOTAL_TOKENS, current + count)
            .putLong(dailyPref, currentDaily + count)
            .apply()

        cleanupOldStats()
    }

    private fun cleanupOldStats() {
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.DAY_OF_YEAR, -30)
        val cutoff = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)

        val editor = prefs.edit()
        var changed = false

        prefs.all.forEach { (key, _) ->
            if (key.startsWith(DAILY_PREFIX)) {
                val datePart = key.substringAfter(DAILY_PREFIX)
                if (datePart < cutoff) {
                    editor.remove(key)
                    changed = true
                }
            }
        }

        if (changed) {
            editor.apply()
        }
    }

    fun getUsedTokens(): Long {
        return prefs.getLong(PREF_TOTAL_TOKENS, 0L)
    }

    fun recordRequest(commandTrigger: String) {
        val currentMonth = getCurrentMonthString()
        val savedMonth = prefs.getString(PREF_CURRENT_MONTH, "")

        val editor = prefs.edit()

        if (savedMonth != currentMonth) {
            editor.putString(PREF_CURRENT_MONTH, currentMonth)
            editor.putInt(PREF_REQUESTS_THIS_MONTH, 1)
        } else {
            val requests = prefs.getInt(PREF_REQUESTS_THIS_MONTH, 0)
            editor.putInt(PREF_REQUESTS_THIS_MONTH, requests + 1)
        }

        val cmdKey = "$CMD_PREFIX$commandTrigger"
        val cmdCount = prefs.getInt(cmdKey, 0)
        editor.putInt(cmdKey, cmdCount + 1)

        editor.apply()
    }

    fun getRequestsThisMonth(): Int {
        val currentMonth = getCurrentMonthString()
        val savedMonth = prefs.getString(PREF_CURRENT_MONTH, "")
        if (savedMonth != currentMonth) {
            return 0
        }
        return prefs.getInt(PREF_REQUESTS_THIS_MONTH, 0)
    }

    fun getFavoriteCommand(): String? {
        var favorite: String? = null
        var maxCount = 0

        prefs.all.forEach { (key, value) ->
            if (key.startsWith(CMD_PREFIX) && value is Int) {
                if (value > maxCount) {
                    maxCount = value
                    favorite = key.substringAfter(CMD_PREFIX)
                }
            }
        }

        return favorite
    }

    fun getTokensLast7Days(): List<Pair<String, Long>> {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val calendar = java.util.Calendar.getInstance()
        val result = mutableListOf<Pair<String, Long>>()

        // Backtrack 6 days (plus today = 7 days)
        calendar.add(java.util.Calendar.DAY_OF_YEAR, -6)

        for (i in 0 until 7) {
            val dateStr = format.format(calendar.time)
            val tokens = prefs.getLong("$DAILY_PREFIX$dateStr", 0L)

            // Format axis label e.g., "15/04"
            val displayFormat = SimpleDateFormat("dd/MM", Locale.US)
            val displayStr = displayFormat.format(calendar.time)

            result.add(Pair(displayStr, tokens))
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return result
    }
}
