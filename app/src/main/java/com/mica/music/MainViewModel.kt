package com.mica.music

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mica.music.data.AppUiSettings
import com.mica.music.data.PlayHistoryStore
import com.mica.music.playback.LibraryPlaybackQueueCoordinator
import com.mica.music.data.MusicLibrary
import com.mica.music.data.Song
import com.mica.music.data.StartupBrowseTarget
import com.mica.music.data.remote.RemotePlayStatsPresentation
import com.mica.music.data.remote.toPlaybackSong
import com.mica.music.playback.asLibraryPlaybackQueueTarget
import com.mica.music.data.preferences.LibraryBrowseSettings
import com.mica.music.playback.toLibraryQueueSyncInput
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 横竖屏等配置变更时保留音乐库与播放控制器，避免重复绑定 MediaSession。 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MicaApp
    val library = MusicLibrary(application)
    val playerController = app.playerController
    val playlistStore = app.playlistStore
    private val transientPlaybackCatalog = app.transientPlaybackCatalog
    val uiSettings = AppUiSettings(application)
    val sleepTimer = app.sleepTimer
    private val playbackStatistics = app.playbackStatistics
    private val remoteCatalogRepository = app.remoteCatalogRepository
    private val remotePlayStatsPresentation = RemotePlayStatsPresentation()
    val remotePlayStats = remotePlayStatsPresentation.stats
    private val libraryPlaybackQueueSync = LibraryPlaybackQueueCoordinator()

    init {
        playbackStatistics.attachPresentationSink(this) { songId, stats ->
            library.applyPlayStats(songId, stats)
            remotePlayStatsPresentation.applyLive(songId, stats)
        }
        viewModelScope.launch {
            remoteCatalogRepository.observeTracksForEnabledSources().collectLatest { tracks ->
                val mediaIds = tracks.map { it.mediaId }
                val persisted = withContext(Dispatchers.IO) {
                    PlayHistoryStore.snapshotStats(application, mediaIds)
                }
                remotePlayStatsPresentation.publishCatalog(mediaIds, persisted)
                // Remote sync can enrich metadata without changing stable IDs. Refresh matching
                // queue entries in place so current/future remote items do not retain stale fields.
                playerController.refreshQueueMetadata(tracks.map { it.toPlaybackSong() })
            }
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
            library.launchAlbumArtCacheMaintenance("startup")
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
        library.release()
        super.onCleared()
    }
}
