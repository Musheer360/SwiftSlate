package com.musheer360.swiftslate.manager

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

class StatsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("stats", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_TOTAL = "total_requests"
        private const val KEY_MONTHLY = "monthly_requests"
        private const val KEY_MONTH = "current_month"
        private const val KEY_COMMAND_COUNTS = "command_counts"
        private const val KEY_DAILY_COUNTS = "daily_counts"
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(System.currentTimeMillis())
    private fun currentMonth(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(System.currentTimeMillis())

    /** Call after a command is successfully processed. */
    @Synchronized
    fun recordUsage(commandName: String) {
        val editor = prefs.edit()

        // Total
        val total = prefs.getInt(KEY_TOTAL, 0) + 1
        editor.putInt(KEY_TOTAL, total)

        // Monthly — reset on rollover
        val storedMonth = prefs.getString(KEY_MONTH, null)
        val month = currentMonth()
        val monthly = if (storedMonth == month) prefs.getInt(KEY_MONTHLY, 0) + 1 else 1
        editor.putString(KEY_MONTH, month)
        editor.putInt(KEY_MONTHLY, monthly)

        // Per-command counts
        val cmdJson = JSONObject(prefs.getString(KEY_COMMAND_COUNTS, "{}") ?: "{}")
        cmdJson.put(commandName, cmdJson.optInt(commandName, 0) + 1)
        editor.putString(KEY_COMMAND_COUNTS, cmdJson.toString())

        // Daily counts — keep last 7 days
        val dailyJson = JSONObject(prefs.getString(KEY_DAILY_COUNTS, "{}") ?: "{}")
        val day = today()
        dailyJson.put(day, dailyJson.optInt(day, 0) + 1)
        // Prune old entries
        val keys = dailyJson.keys().asSequence().sorted().toList()
        if (keys.size > 7) {
            keys.dropLast(7).forEach { dailyJson.remove(it) }
        }
        editor.putString(KEY_DAILY_COUNTS, dailyJson.toString())

        editor.apply()
    }

    val totalRequests: Int get() = prefs.getInt(KEY_TOTAL, 0)

    val monthlyRequests: Int
        get() {
            if (prefs.getString(KEY_MONTH, null) != currentMonth()) return 0
            return prefs.getInt(KEY_MONTHLY, 0)
        }

    /** Returns the command name with the highest usage, or null. */
    val favoriteCommand: String?
        get() {
            val json = JSONObject(prefs.getString(KEY_COMMAND_COUNTS, "{}") ?: "{}")
            var best: String? = null
            var bestCount = 0
            json.keys().forEach { key ->
                val count = json.optInt(key, 0)
                if (count > bestCount) { bestCount = count; best = key }
            }
            return best
        }

    /** Returns daily counts for the last 7 days, ordered oldest-first. Missing days are 0. */
    fun dailyCounts(): List<Pair<String, Int>> {
        val json = JSONObject(prefs.getString(KEY_DAILY_COUNTS, "{}") ?: "{}")
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = java.util.Calendar.getInstance()
        val result = mutableListOf<Pair<String, Int>>()
        // Go back 6 days from today
        cal.add(java.util.Calendar.DAY_OF_YEAR, -6)
        repeat(7) {
            val day = dayFmt.format(cal.time)
            result.add(day to json.optInt(day, 0))
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return result
    }
}
