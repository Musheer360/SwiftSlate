package com.musheer360.swiftslate

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.musheer360.swiftslate.update.UpdateWorker
import java.util.concurrent.TimeUnit

class SwiftSlateApp : Application() {

    override fun onCreate() {
        super.onCreate()
        scheduleUpdateCheck()
    }

    private fun scheduleUpdateCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "update_check",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}
