package com.mica.music

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mica.music.data.AppUiSettings
import com.mica.music.data.LibraryPlaybackQueueCoordinator
import com.mica.music.data.MusicLibrary
import com.mica.music.data.SleepTimerController
import com.mica.music.data.Song
import com.mica.music.data.StartupBrowseTarget
import com.mica.music.data.asLibraryPlaybackQueueTarget
import com.mica.music.data.preferences.LibraryBrowseSettings
import com.mica.music.data.scanner.ScanCacheManager
import com.mica.music.data.toLibraryQueueSyncInput
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.launch

/** 横竖屏等配置变更时保留音乐库与播放控制器，避免重复绑定 MediaSession。 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val library = MusicLibrary(application)
    val playerController = (application as MicaApp).playerController
    val playlistStore = (application as MicaApp).playlistStore
    private val transientPlaybackCatalog = (application as MicaApp).transientPlaybackCatalog
    val uiSettings = AppUiSettings(application)
    val sleepTimer = SleepTimerController(viewModelScope, playerController, application)
    private val playbackStatistics = (application as MicaApp).playbackStatistics
    private val libraryPlaybackQueueSync = LibraryPlaybackQueueCoordinator()

    init {
        playbackStatistics.attachPresentationSink(this) { songId, stats ->
            library.applyPlayStats(songId, stats)
        }
        viewModelScope.launch {
            val startupStartedMs = SystemClock.elapsedRealtime()
            DiagnosticLog.event("LibraryStartup", "loadCached start")
            val startupBrowseTarget = when (LibraryBrowseSettings.lastHomeSection(application)) {
                "Artists" -> StartupBrowseTarget.ARTISTS
                "Albums" -> StartupBrowseTarget.ALBUMS
                else -> StartupBrowseTarget.NONE
            }
            library.loadCachedLibrary(startupBrowseTarget)
            // The identity migration runs inside the library DB load. Refresh the eagerly
            // constructed preference-backed playlist store after that migration completes.
            playlistStore.reloadFromStorage()
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
                library.launchArtworkCacheRepairIfNeeded("startup")
            }
        }
    }

    fun syncPlaybackQueueWithLibrarySongs(reason: String = "libraryIds") {
        libraryPlaybackQueueSync.sync(
            reason = reason,
            library = library.toLibraryQueueSyncInput(::resolveSong),
            player = playerController.asLibraryPlaybackQueueTarget(),
        )
    }

    fun resolveSong(id: String): Song? =
        transientPlaybackCatalog.songById(id) ?: library.songById(id)

    fun refreshSongMetadataAfterTagEditor(songId: String) {
        library.launchRefreshSongMetadata(songId)
    }

    override fun onCleared() {
        playbackStatistics.detachPresentationSink(this)
        sleepTimer.cancel()
        library.release()
        super.onCleared()
    }
}
