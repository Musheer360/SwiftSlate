package com.musheer360.swiftslate.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val version: String,
    val downloadUrl: String
)

object UpdateChecker {

    private const val RELEASES_URL =
        "https://api.github.com/repos/Musheer360/SwiftSlate/releases/latest"
    private const val PREFS_NAME = "update_prefs"
    private const val KEY_VERSION = "update_available_version"
    private const val KEY_DOWNLOAD_URL = "update_download_url"
    private const val KEY_LAST_CHECK = "update_last_check"

    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            if (connection.responseCode !in 200..299) return@withContext null

            val body = connection.inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream)).use { it.readText() }
            }

            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "")
            if (tagName.isBlank()) return@withContext null

            val latestVersion = tagName.removePrefix("v")
            if (!isNewer(latestVersion, currentVersion)) return@withContext null

            val assets = json.optJSONArray("assets")
            if (assets == null || assets.length() == 0) return@withContext null

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk")) {
                    val downloadUrl = asset.optString("browser_download_url", "")
                    if (downloadUrl.isNotBlank()) {
                        return@withContext UpdateInfo(latestVersion, downloadUrl)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun getCachedUpdate(context: Context): UpdateInfo? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        val url = prefs.getString(KEY_DOWNLOAD_URL, null) ?: return null
        return UpdateInfo(version, url)
    }

    fun cacheUpdate(context: Context, info: UpdateInfo) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_VERSION, info.version)
            .putString(KEY_DOWNLOAD_URL, info.downloadUrl)
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()
    }

    fun clearCache(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun isNewer(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val size = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until size) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
