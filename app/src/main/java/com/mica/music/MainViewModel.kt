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
            }
        }
    }

    override fun onCleared() {
        sleepTimer.cancel()
        library.release()
        super.onCleared()
    }
}
