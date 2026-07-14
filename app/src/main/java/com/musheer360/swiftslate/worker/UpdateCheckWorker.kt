package com.musheer360.swiftslate.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.musheer360.swiftslate.BuildConfig
import com.musheer360.swiftslate.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val CHANNEL_ID = "update_channel"
        private const val NOTIFICATION_ID = 9001
        private const val MAX_RESPONSE_CHARS = 1_048_576
        private const val PREFS_NAME = "update_check"
        private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
        private const val GITHUB_API_URL =
            "https://api.github.com/repos/Musheer360/SwiftSlate/releases/latest"
        private const val RELEASES_URL =
            "https://github.com/Musheer360/SwiftSlate/releases/latest"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val release = fetchLatestRelease() ?: return@withContext Result.retry()
            val latestTag = release.optString("tag_name", "").removePrefix("v")
            val currentVersion = BuildConfig.VERSION_NAME.removePrefix("v")

            if (latestTag.isBlank() || currentVersion.isBlank()) return@withContext Result.success()

            if (isNewer(latestTag, currentVersion) && !alreadyNotified(latestTag)) {
                showNotification()
                markNotified(latestTag)
            }

            Result.success()
        } catch (e: Exception) {
            // Retry only transient I/O failures. Non-I/O errors (e.g. malformed JSON)
            // would otherwise retry indefinitely — skip and let the next periodic run try.
            if (e is java.io.IOException) Result.retry() else Result.success()
        }
    }

    private fun fetchLatestRelease(): JSONObject? {
        val url = URL(GITHUB_API_URL)
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            if (connection.responseCode != 200) return null

            // Bounded read — avoids unbounded memory usage on unexpectedly large responses
            val body = connection.inputStream.bufferedReader().use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var n: Int
                while (reader.read(buf).also { n = it } != -1) {
                    if (sb.length + n > MAX_RESPONSE_CHARS) return null
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Compares semantic versions (e.g. "1.0.50" > "1.0.49").
     * Returns true if [latest] is strictly newer than [current].
     */
    private fun isNewer(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun alreadyNotified(version: String): Boolean {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_NOTIFIED_VERSION, null) == version
    }

    private fun markNotified(version: String) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_NOTIFIED_VERSION, version).apply()
    }

    private fun showNotification() {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create channel for Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies when a new version of SwiftSlate is available"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SwiftSlate update available")
            .setContentText("Tap to view the latest release")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
