package com.musheer360.swiftslate

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.musheer360.swiftslate.worker.UpdateCheckWorker
import java.util.concurrent.TimeUnit

class SwiftSlateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Pre-warm SharedPreferences — triggers async disk load so they're
        // in memory by the time the ViewModel creates managers
        getSharedPreferences("settings", Context.MODE_PRIVATE)
        getSharedPreferences("commands", Context.MODE_PRIVATE)
        getSharedPreferences("secure_keys_prefs", Context.MODE_PRIVATE)
        getSharedPreferences("stats", Context.MODE_PRIVATE)

        scheduleUpdateCheck()
    }

    private fun scheduleUpdateCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val updateRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            24, TimeUnit.HOURS,
            6, TimeUnit.HOURS  // Flex window: system picks best time in last 6h
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "update_check",
            ExistingPeriodicWorkPolicy.KEEP,
            updateRequest
        )
    }
}
