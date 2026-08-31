package com.mica.music.data.remote

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mica.music.data.preferences.RemoteAutoSyncPreferences
import com.mica.music.util.DiagnosticLog
import java.util.concurrent.TimeUnit

internal object RemoteAutoSyncScheduler {
    const val STALE_AFTER_MS: Long = 6L * 60L * 60L * 1000L
    private const val PERIOD_HOURS = 6L
    private const val PERIODIC_WORK_NAME = "remote-music-periodic-sync"
    private const val STARTUP_WORK_NAME = "remote-music-startup-sync"

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun install(context: Context) {
        if (!RemoteAutoSyncPreferences.enabled(context)) {
            cancel(context)
            return
        }
        val workManager = workManagerOrNull(context) ?: return
        val periodic = PeriodicWorkRequestBuilder<RemoteAutoSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(networkConstraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
    }

    fun requestCatchUp(context: Context) {
        if (!RemoteAutoSyncPreferences.enabled(context)) return
        workManagerOrNull(context)?.let(::requestCatchUp)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        RemoteAutoSyncPreferences.setEnabled(context, enabled)
        if (enabled) {
            install(context)
            requestCatchUp(context)
        } else {
            cancel(context)
        }
    }

    private fun cancel(context: Context) {
        workManagerOrNull(context)?.let { workManager ->
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(STARTUP_WORK_NAME)
        }
    }
    private fun requestCatchUp(workManager: WorkManager) {
        val request = OneTimeWorkRequestBuilder<RemoteAutoSyncWorker>()
            .setConstraints(networkConstraints)
            .build()
        workManager.enqueueUniqueWork(
            STARTUP_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    private fun workManagerOrNull(context: Context): WorkManager? {
        if (Build.FINGERPRINT == "robolectric") return null
        return runCatching { WorkManager.getInstance(context.applicationContext) }
            .onFailure { DiagnosticLog.event("RemoteSync", "WorkManager unavailable; automatic sync not scheduled", it) }
            .getOrNull()
    }
}