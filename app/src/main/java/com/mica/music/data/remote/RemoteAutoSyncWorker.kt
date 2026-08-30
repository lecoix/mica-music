package com.mica.music.data.remote

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mica.music.MicaApp
import com.mica.music.data.preferences.RemoteAutoSyncPreferences
import com.mica.music.util.DiagnosticLog

internal class RemoteAutoSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!RemoteAutoSyncPreferences.enabled(applicationContext)) return Result.success()
        val app = applicationContext as? MicaApp ?: return Result.failure()
        val result = runCatching {
            app.remoteSourceManager.syncEnabledSourcesIfStale(
                staleAfterMs = RemoteAutoSyncScheduler.STALE_AFTER_MS,
            )
        }.getOrElse { error ->
            DiagnosticLog.event("RemoteSync", "worker failed before source iteration", error)
            return Result.success()
        }
        DiagnosticLog.event(
            "RemoteSync",
            "attempted=${result.attempted}; succeeded=${result.succeeded}; failed=${result.failedSourceIds.size}" +
                result.failedSourceIds.takeIf { it.isNotEmpty() }?.joinToString(prefix = "; ids=").orEmpty(),
        )
        return Result.success()
    }
}