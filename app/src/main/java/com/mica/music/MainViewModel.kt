package com.mica.music

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mica.music.data.AppUiSettings
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlayerController
import kotlinx.coroutines.launch

/** 横竖屏等配置变更时保留音乐库与播放控制器，避免重复绑定 MediaSession。 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val library = MusicLibrary(application)
    val playerController = PlayerController(application)
    val uiSettings = AppUiSettings(application)

    init {
        playerController.onSongPlayStarted = { songId -> library.onSongPlayed(songId) }
        viewModelScope.launch {
            library.loadCachedLibrary()
        }
    }

    override fun onCleared() {
        playerController.release()
        super.onCleared()
    }
}
