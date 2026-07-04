package com.mica.music

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mica.music.data.AppUiSettings
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlayerController
import com.mica.music.data.SleepTimerController
import com.mica.music.data.scanner.ScanCacheManager
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.launch

/** 横竖屏等配置变更时保留音乐库与播放控制器，避免重复绑定 MediaSession。 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val library = MusicLibrary(application)
    val playerController = (application as MicaApp).playerController
    val uiSettings = AppUiSettings(application)
    val sleepTimer = SleepTimerController(viewModelScope, playerController)
    private val libraryQueueSyncPolicy = LibraryQueueSyncPolicy()

    init {
        playerController.onSongPlayStarted = { songId -> library.onSongPlayed(songId) }
        playerController.onSongListenSecondsAdded = { songId, seconds ->
            library.onSongListened(songId, seconds)
        }
        viewModelScope.launch {
            val startupStartedMs = SystemClock.elapsedRealtime()
            DiagnosticLog.event("LibraryStartup", "loadCached start")
            library.loadCachedLibrary()
            val songs = library.songs
            DiagnosticLog.event(
                "LibraryStartup",
                "loadCached returned durMs=${SystemClock.elapsedRealtime() - startupStartedMs} " +
                    "songs=${songs.size} hasScanned=${library.hasScanned}",
            )
            val pruneStartedMs = SystemClock.elapsedRealtime()
            ScanCacheManager.pruneAlbumArtCache(application, songs)
            DiagnosticLog.event(
                "LibraryStartup",
                "pruneAlbumArt end durMs=${SystemClock.elapsedRealtime() - pruneStartedMs} songs=${songs.size}",
            )
            if (songs.isNotEmpty()) {
                playerController.songResolver = library::songById
                DiagnosticLog.event("LibraryStartup", "connectIfNeeded start songs=${songs.size}")
                playerController.connectIfNeeded()
                val bootstrapStartedMs = SystemClock.elapsedRealtime()
                val bootstrapped = playerController.bootstrapQueue(library::songById)
                DiagnosticLog.event(
                    "LibraryStartup",
                    "bootstrapQueue result=$bootstrapped durMs=${SystemClock.elapsedRealtime() - bootstrapStartedMs} " +
                        "queue=${playerController.songQueue.size}",
                )
                if (!bootstrapped) {
                    val setQueueStartedMs = SystemClock.elapsedRealtime()
                    playerController.setQueue(songs)
                    DiagnosticLog.event(
                        "LibraryStartup",
                        "initial setQueue end durMs=${SystemClock.elapsedRealtime() - setQueueStartedMs} " +
                            "queue=${playerController.songQueue.size}",
                    )
                }
                library.launchArtworkCacheRepairIfNeeded("startup")
            }
        }
    }

    fun syncPlaybackQueueWithLibrarySongs() {
        val effectStartedMs = SystemClock.elapsedRealtime()
        val songs = library.songs
        val currentQueueIds = playerController.songQueue.map { it.id }
        when (
            val plan = libraryQueueSyncPolicy.plan(
                songs = songs,
                libraryIds = library.songIds,
                currentQueueIds = currentQueueIds,
            )
        ) {
            LibraryQueueSyncPlan.SkipEmpty -> {
                DiagnosticLog.event(
                    "LibraryQueue",
                    "librarySongs effect skipped empty hasScanned=${library.hasScanned}",
                )
            }
            is LibraryQueueSyncPlan.BootstrapOrSetQueue -> {
                logLibraryQueueEffectStart(plan, songs.size, currentQueueIds.size)
                val bootstrapped = playerController.bootstrapQueue(library::songById)
                DiagnosticLog.event(
                    "LibraryQueue",
                    "librarySongs bootstrap result=$bootstrapped " +
                        "durMs=${SystemClock.elapsedRealtime() - effectStartedMs}",
                )
                if (!bootstrapped) {
                    playerController.setQueue(plan.songs)
                }
                logLibraryQueueEffectEnd(effectStartedMs)
            }
            is LibraryQueueSyncPlan.SetQueue -> {
                logLibraryQueueEffectStart(plan, songs.size, currentQueueIds.size)
                playerController.setQueue(plan.songs)
                logLibraryQueueEffectEnd(effectStartedMs)
            }
            is LibraryQueueSyncPlan.RefreshMetadata -> {
                logLibraryQueueEffectStart(plan, songs.size, currentQueueIds.size)
                playerController.refreshQueueMetadata(plan.songs)
                logLibraryQueueEffectEnd(effectStartedMs)
            }
        }
    }

    private fun logLibraryQueueEffectStart(
        plan: LibraryQueueSyncPlan,
        songsSize: Int,
        currentQueueSize: Int,
    ) {
        DiagnosticLog.event(
            "LibraryQueue",
            "librarySongs effect start songs=$songsSize queue=$currentQueueSize " +
                "previousLibrary=${plan.previousLibraryIdsSize()} " +
                "currentQueueWasLibrary=${plan.currentQueueWasLibrary()}",
        )
    }

    private fun logLibraryQueueEffectEnd(startedMs: Long) {
        DiagnosticLog.event(
            "LibraryQueue",
            "librarySongs effect end durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                "queue=${playerController.songQueue.size}",
        )
    }

    override fun onCleared() {
        sleepTimer.cancel()
        library.release()
        super.onCleared()
    }
}

private fun LibraryQueueSyncPlan.previousLibraryIdsSize(): Int =
    when (this) {
        LibraryQueueSyncPlan.SkipEmpty -> 0
        is LibraryQueueSyncPlan.BootstrapOrSetQueue -> previousLibraryIdsSize
        is LibraryQueueSyncPlan.SetQueue -> previousLibraryIdsSize
        is LibraryQueueSyncPlan.RefreshMetadata -> previousLibraryIdsSize
    }

private fun LibraryQueueSyncPlan.currentQueueWasLibrary(): Boolean =
    when (this) {
        LibraryQueueSyncPlan.SkipEmpty -> false
        is LibraryQueueSyncPlan.BootstrapOrSetQueue -> currentQueueWasLibrary
        is LibraryQueueSyncPlan.SetQueue -> currentQueueWasLibrary
        is LibraryQueueSyncPlan.RefreshMetadata -> currentQueueWasLibrary
    }
