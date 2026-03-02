package com.musheer360.swiftslate.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.musheer360.swiftslate.BuildConfig
import com.musheer360.swiftslate.MainActivity
import com.musheer360.swiftslate.R

class UpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val CHANNEL_ID = "app_updates"
        const val NOTIFICATION_ID = 9001
        const val EXTRA_NAVIGATE_TO = "navigate_to"
        const val NAV_SETTINGS = "settings"
    }

    override suspend fun doWork(): Result {
        val currentVersion = BuildConfig.VERSION_NAME

        // Skip check for development builds
        if (currentVersion.contains("dev")) return Result.success()

        val update = UpdateChecker.checkForUpdate(currentVersion)

        if (update != null) {
            UpdateChecker.cacheUpdate(applicationContext, update)
            showNotification(update)
        } else {
            // Clear stale data if current version matches or exceeds cached version
            val cached = UpdateChecker.getCachedUpdate(applicationContext)
            if (cached != null && !UpdateChecker.isNewer(cached.version, currentVersion)) {
                UpdateChecker.clearCache(applicationContext)
            }
        }

        return Result.success()
    }

    private fun showNotification(update: UpdateInfo) {
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_TO, NAV_SETTINGS)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SwiftSlate v${update.version} available")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for new SwiftSlate versions"
            }
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
