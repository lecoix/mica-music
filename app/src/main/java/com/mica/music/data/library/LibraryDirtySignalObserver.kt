package com.mica.music.data.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.mica.music.data.ScanSource
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground-only dirty-signal adapter for automatic library synchronization.
 *
 * Signals only enter [LibrarySyncScheduler.markDirty]. After the r5 readiness review, FOLDER
 * scheduled AUTO may publish only through the orchestrator's normal token/publication gates.
 */
internal class LibraryDirtySignalObserver(
    context: Context,
    private val scope: CoroutineScope,
    private val markDirty: (LibraryOperationCause) -> Long,
    private val activeSource: () -> ScanSource?,
    private val safVerifyIntervalMs: Long = DEFAULT_SAF_VERIFY_INTERVAL_MS,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var foreground = false
    private var released = false
    private var safVerifyJob: Job? = null

    private val audioObserver = causeObserver(LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY)
    private val filesObserver = causeObserver(LibraryOperationCause.MEDIASTORE_FILES_DIRTY)

    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val cause = when (intent?.action) {
                Intent.ACTION_MEDIA_SCANNER_FINISHED ->
                    LibraryOperationCause.MEDIA_SCANNER_FINISHED
                Intent.ACTION_MEDIA_MOUNTED,
                Intent.ACTION_MEDIA_UNMOUNTED,
                Intent.ACTION_MEDIA_REMOVED,
                Intent.ACTION_MEDIA_EJECT,
                -> LibraryOperationCause.STORAGE_CHANGED
                else -> return
            }
            signal(cause)
        }
    }

    fun onForegroundChanged(inForeground: Boolean) {
        if (released || foreground == inForeground) return
        foreground = inForeground
        if (inForeground) {
            registerSignals()
            signal(LibraryOperationCause.FOREGROUND_CATCH_UP)
            refreshSafVerifyLoop()
        } else {
            unregisterSignals()
            safVerifyJob?.cancel()
            safVerifyJob = null
        }
    }

    fun onActiveSourceChanged() {
        if (!foreground || released) return
        // Foreground may be entered before cached/persisted library authority is restored.
        // The initial FOREGROUND_CATCH_UP is then intentionally ignored while intent is still
        // UNINITIALIZED. Re-signal after source activation so a generation change that happened
        // during startup cannot remain invisible until the next provider callback.
        signal(LibraryOperationCause.FOREGROUND_CATCH_UP)
        refreshSafVerifyLoop()
    }

    fun release() {
        if (released) return
        released = true
        foreground = false
        unregisterSignals()
        safVerifyJob?.cancel()
        safVerifyJob = null
    }

    private fun causeObserver(cause: LibraryOperationCause): ContentObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                signal(cause)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                signal(cause)
            }
        }

    private fun signal(cause: LibraryOperationCause) {
        if (!foreground || released) return
        markDirty(cause)
    }

    private fun registerSignals() {
        runCatching {
            appContext.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                audioObserver,
            )
            appContext.contentResolver.registerContentObserver(
                MediaStore.Files.getContentUri("external"),
                true,
                filesObserver,
            )
        }.onFailure { error ->
            DiagnosticLog.event("LibraryAutoSync", "shadow observer registration failed", error)
            unregisterContentObservers()
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED)
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                storageReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { error ->
            DiagnosticLog.event("LibraryAutoSync", "shadow storage receiver registration failed", error)
        }
    }

    private fun unregisterSignals() {
        unregisterContentObservers()
        runCatching { appContext.unregisterReceiver(storageReceiver) }
    }

    private fun unregisterContentObservers() {
        runCatching { appContext.contentResolver.unregisterContentObserver(audioObserver) }
        runCatching { appContext.contentResolver.unregisterContentObserver(filesObserver) }
    }

    private fun refreshSafVerifyLoop() {
        safVerifyJob?.cancel()
        safVerifyJob = null
        if (activeSource() != ScanSource.FOLDER || safVerifyIntervalMs <= 0L) return

        safVerifyJob = scope.launch {
            while (isActive) {
                delay(safVerifyIntervalMs)
                if (!foreground || released || activeSource() != ScanSource.FOLDER) break
                signal(LibraryOperationCause.SAF_PERIODIC_VERIFY)
            }
        }
    }

    companion object {
        const val DEFAULT_SAF_VERIFY_INTERVAL_MS = 5L * 60L * 1000L
    }
}
