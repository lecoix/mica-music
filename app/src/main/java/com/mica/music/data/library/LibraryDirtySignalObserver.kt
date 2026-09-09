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
import com.mica.music.data.scanner.AndroidDeviceMediaStoreGenerationApi
import com.mica.music.data.scanner.DeviceGenerationSnapshot
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val activeSafTreeUri: () -> Uri? = { null },
    private val readMediaStoreGeneration: suspend () -> DeviceGenerationSnapshot = {
        withContext(Dispatchers.IO) {
            AndroidDeviceMediaStoreGenerationApi(context.applicationContext).read()
        }
    },
    private val safGenerationPollIntervalMs: Long = DEFAULT_SAF_GENERATION_POLL_INTERVAL_MS,
    private val safVerifyIntervalMs: Long = DEFAULT_SAF_VERIFY_INTERVAL_MS,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var foreground = false
    private var released = false
    private var safVerifyJob: Job? = null
    private var safGenerationWatchJob: Job? = null
    private var safGenerationBaseline: DeviceGenerationSnapshot.Available? = null
    private var registeredSafTreeUri: Uri? = null

    private val audioObserver = causeObserver(LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY)
    private val filesObserver = causeObserver(LibraryOperationCause.MEDIASTORE_FILES_DIRTY)
    private val safTreeObserver = causeObserver(LibraryOperationCause.SAF_TREE_DIRTY)

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
            refreshSafTreeObserver()
            signal(LibraryOperationCause.FOREGROUND_CATCH_UP)
            refreshSafVerifyLoop()
            refreshSafGenerationWatchLoop()
        } else {
            unregisterSignals()
            safVerifyJob?.cancel()
            safVerifyJob = null
            stopSafGenerationWatch()
        }
    }

    fun onActiveSourceChanged() {
        if (!foreground || released) return
        // Foreground may be entered before cached/persisted library authority is restored.
        // The initial FOREGROUND_CATCH_UP is then intentionally ignored while intent is still
        // UNINITIALIZED. Re-signal after source activation so a generation change that happened
        // during startup cannot remain invisible until the next provider callback.
        refreshSafTreeObserver()
        signal(LibraryOperationCause.FOREGROUND_CATCH_UP)
        refreshSafVerifyLoop()
        refreshSafGenerationWatchLoop()
    }

    fun release() {
        if (released) return
        released = true
        foreground = false
        unregisterSignals()
        safVerifyJob?.cancel()
        safVerifyJob = null
        stopSafGenerationWatch()
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
        unregisterSafTreeObserver()
    }

    private fun refreshSafTreeObserver() {
        unregisterSafTreeObserver()
        if (!foreground || released || activeSource() != ScanSource.FOLDER) return
        val treeUri = activeSafTreeUri() ?: return

        runCatching {
            appContext.contentResolver.registerContentObserver(
                treeUri,
                true,
                safTreeObserver,
            )
            registeredSafTreeUri = treeUri
        }.onFailure { error ->
            registeredSafTreeUri = null
            DiagnosticLog.event(
                "LibraryAutoSync",
                "saf tree observer registration failed uri=$treeUri",
                error,
            )
        }
    }

    private fun unregisterSafTreeObserver() {
        if (registeredSafTreeUri == null) return
        runCatching { appContext.contentResolver.unregisterContentObserver(safTreeObserver) }
        registeredSafTreeUri = null
    }

    private fun refreshSafGenerationWatchLoop() {
        stopSafGenerationWatch()
        if (
            activeSource() != ScanSource.FOLDER ||
            safGenerationPollIntervalMs <= 0L
        ) return

        safGenerationWatchJob = scope.launch {
            while (isActive) {
                val snapshot = try {
                    readMediaStoreGeneration()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                if (!foreground || released || activeSource() != ScanSource.FOLDER) break
                if (snapshot is DeviceGenerationSnapshot.Available) {
                    val previous = safGenerationBaseline
                    safGenerationBaseline = snapshot
                    if (previous != null && mediaStoreGenerationChanged(previous, snapshot)) {
                        // MediaStore callbacks are only accelerators for FOLDER authority and can be
                        // missed by OEM providers (notably IS_TRASHED transitions). Polling the cheap
                        // provider generation restores that accelerator without turning the 5-minute
                        // SAF correctness verify into a high-frequency O(N) tree walk.
                        DiagnosticLog.event(
                            "LibraryAutoSync",
                            "saf generation accelerator changed before=${previous.volumes} after=${snapshot.volumes}",
                        )
                        signal(LibraryOperationCause.MEDIASTORE_FILES_DIRTY)
                    }
                }
                delay(safGenerationPollIntervalMs)
            }
        }
    }

    private fun stopSafGenerationWatch() {
        safGenerationWatchJob?.cancel()
        safGenerationWatchJob = null
        safGenerationBaseline = null
    }

    private fun mediaStoreGenerationChanged(
        previous: DeviceGenerationSnapshot.Available,
        current: DeviceGenerationSnapshot.Available,
    ): Boolean {
        if (previous.volumes.keys != current.volumes.keys) return true
        return current.volumes.any { (volumeName, now) ->
            val before = previous.volumes[volumeName] ?: return@any true
            before.providerVersion != now.providerVersion || before.generation != now.generation
        }
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
        // Generation reads are a cheap foreground accelerator only. They never replace the
        // authoritative periodic SAF metadata walk below.
        const val DEFAULT_SAF_GENERATION_POLL_INTERVAL_MS = 3_000L
        const val DEFAULT_SAF_VERIFY_INTERVAL_MS = 5L * 60L * 1000L
    }
}
