package com.mica.music

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mica.music.data.AppUiSettings
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlaybackSessionStore
import com.mica.music.data.PlayerController
import com.mica.music.data.SleepTimerController
import com.mica.music.data.scanner.ScanCacheManager
import kotlinx.coroutines.launch

/** 横竖屏等配置变更时保留音乐库与播放控制器，避免重复绑定 MediaSession。 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val library = MusicLibrary(application)
    val playerController = PlayerController(application)
    val uiSettings = AppUiSettings(application)
    val sleepTimer = SleepTimerController(viewModelScope, playerController)

    init {
        playerController.onSongPlayStarted = { songId -> library.onSongPlayed(songId) }
        viewModelScope.launch {
            library.loadCachedLibrary()
            val songs = library.songs
            ScanCacheManager.pruneAlbumArtCache(application, songs)
            if (songs.isNotEmpty()) {
                val session = PlaybackSessionStore.load(application)
                playerController.setQueue(songs)
                session?.let { playerController.restoreSession(it) }
                playerController.reconcileRestoredSessionIndex()
            }
        }
    }

    override fun onCleared() {
        sleepTimer.cancel()
        playerController.persistPlaybackSessionNow()
        playerController.release()
        super.onCleared()
    }
}
