package com.mica.music

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mica.music.data.AppUiSettings
import com.mica.music.data.LibraryPlaybackQueueCoordinator
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlayerController
import com.mica.music.data.SleepTimerController
import com.mica.music.data.asLibraryPlaybackQueueTarget
import com.mica.music.data.scanner.ScanCacheManager
import com.mica.music.data.toLibraryQueueSyncInput
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.launch

/** 横竖屏等配置变更时保留音乐库与播放控制器，避免重复绑定 MediaSession。 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val library = MusicLibrary(application)
    val playerController = (application as MicaApp).playerController
    val uiSettings = AppUiSettings(application)
    val sleepTimer = SleepTimerController(viewModelScope, playerController)
    private val libraryPlaybackQueueSync = LibraryPlaybackQueueCoordinator()

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
                library.launchArtworkCacheRepairIfNeeded("startup")
            }
        }
    }

    fun syncPlaybackQueueWithLibrarySongs(reason: String = "libraryIds") {
        libraryPlaybackQueueSync.sync(
            reason = reason,
            library = library.toLibraryQueueSyncInput(),
            player = playerController.asLibraryPlaybackQueueTarget(),
        )
    }

    override fun onCleared() {
        sleepTimer.cancel()
        library.release()
        super.onCleared()
    }
}
