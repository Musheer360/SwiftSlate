package com.musheer360.swiftslate

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.musheer360.swiftslate.manager.KeyManager
import com.musheer360.swiftslate.worker.UpdateCheckWorker
import java.util.concurrent.TimeUnit

class SwiftSlateApp : Application() {
    /**
     * The one KeyManager for the process. Rate-limit benching, invalid-key marks and the
     * round-robin cursor are in-memory only, so a second instance starts blind: it re-tries keys
     * another instance already knows are benched, and never learns what that one learned. It also
     * breaks [KeyManager.addKey]'s un-benching — re-adding a key in the UI cleared the invalid
     * mark on the UI's instance while the accessibility service kept benching it for the full TTL.
     *
     * Lazy so the Keystore round trip in the constructor stays off Application.onCreate.
     */
    val keyManager: KeyManager by lazy { KeyManager(this) }

    companion object {
        const val TAG = "SwiftSlateCrash"
        /** Pref key (settings store) holding the timestamp of the last uncaught process crash. */
        const val PREF_SERVICE_DIED_AT = "service_died_at"
    }

    override fun onCreate() {
        super.onCreate()
        // Pre-warm SharedPreferences — triggers async disk load so they're
        // in memory by the time the ViewModel creates managers
        getSharedPreferences("settings", Context.MODE_PRIVATE)
        getSharedPreferences("commands", Context.MODE_PRIVATE)
        getSharedPreferences("secure_keys_prefs", Context.MODE_PRIVATE)
        getSharedPreferences("stats", Context.MODE_PRIVATE)

        installCrashMarker()
        scheduleUpdateCheck()
    }

    /**
     * Records every uncaught exception in this (shared) process before the platform kills it,
     * so the Dashboard can tell the user the service died and offer a one-tap re-enable. The
     * accessibility service has no UI to crash into, and a UI crash takes the service down with
     * it — without this marker both look like a silent "went inactive" (#125). The previous
     * handler is still invoked afterwards; the process still dies as the platform intends.
     */
    private fun installCrashMarker() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "uncaught exception on thread " + thread.name, throwable)
                getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .edit().putLong(PREF_SERVICE_DIED_AT, System.currentTimeMillis()).apply()
            } catch (_: Exception) {
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
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
