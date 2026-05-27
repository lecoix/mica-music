package com.mica.music.data

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mica.music.media.MicaSpectrumAnalyzer
import com.mica.music.ui.theme.MicaPreset

/**
 * 界面偏好（主题、状态栏、强调色、云母背景等），供 [com.mica.music.MainActivity] 与设置页共享并即时刷新。
 */
class AppUiSettings(context: Context) {

    private val appContext = context.applicationContext

    var themeMode by mutableStateOf(AppPreferences.themeMode(appContext))
        private set

    var hideStatusBar by mutableStateOf(AppPreferences.hideStatusBar(appContext))
        private set

    var playerLowerBackground by mutableStateOf(AppPreferences.playerLowerBackground(appContext))
        private set

    var coverEdgeProgress by mutableStateOf(AppPreferences.coverEdgeProgress(appContext))
        private set

    var playerImmersiveLower by mutableStateOf(AppPreferences.playerImmersiveLower(appContext))
        private set

    var miniPlayerStyle by mutableStateOf(AppPreferences.miniPlayerStyle(appContext))
        private set

    var coverDisplayMode by mutableStateOf(AppPreferences.coverDisplayMode(appContext))
        private set

    var playerCoverFlowMode by mutableStateOf(AppPreferences.playerCoverFlowMode(appContext))
        private set

    var accentColor by mutableStateOf(AppPreferences.appAccentColor(appContext))
        private set

    var micaBackgroundPreset by mutableStateOf(AppPreferences.micaBackgroundPreset(appContext))
        private set

    var lyricSplitEnabled by mutableStateOf(AppPreferences.lyricSplitEnabled(appContext))
        private set

    var spectrumEnabled by mutableStateOf(AppPreferences.spectrumEnabled(appContext))
        private set

    init {
        syncSpectrumAnalyzer()
    }

    fun updateThemeMode(mode: AppThemeMode) {
        themeMode = mode
        AppPreferences.setThemeMode(appContext, mode)
    }

    fun updateHideStatusBar(hide: Boolean) {
        hideStatusBar = hide
        AppPreferences.setHideStatusBar(appContext, hide)
    }

    fun updatePlayerLowerBackground(mode: PlayerLowerBackgroundMode) {
        playerLowerBackground = mode
        AppPreferences.setPlayerLowerBackground(appContext, mode)
    }

    fun updateCoverEdgeProgress(enabled: Boolean) {
        coverEdgeProgress = enabled
        AppPreferences.setCoverEdgeProgress(appContext, enabled)
    }

    fun updatePlayerImmersiveLower(enabled: Boolean) {
        playerImmersiveLower = enabled
        AppPreferences.setPlayerImmersiveLower(appContext, enabled)
    }

    fun updateMiniPlayerStyle(style: MiniPlayerStyle) {
        miniPlayerStyle = style
        AppPreferences.setMiniPlayerStyle(appContext, style)
        syncSpectrumAnalyzer()
    }

    fun updateCoverDisplayMode(mode: CoverDisplayMode) {
        coverDisplayMode = mode
        AppPreferences.setCoverDisplayMode(appContext, mode)
    }

    fun updatePlayerCoverFlowMode(mode: PlayerCoverFlowMode) {
        playerCoverFlowMode = mode
        AppPreferences.setPlayerCoverFlowMode(appContext, mode)
    }

    fun updateAccentColor(accent: AppAccentColor) {
        accentColor = accent
        AppPreferences.setAppAccentColor(appContext, accent)
    }

    fun updateMicaBackgroundPreset(preset: MicaPreset) {
        micaBackgroundPreset = preset
        AppPreferences.setMicaBackgroundPreset(appContext, preset)
    }

    fun updateLyricSplitEnabled(enabled: Boolean) {
        lyricSplitEnabled = enabled
        AppPreferences.setLyricSplitEnabled(appContext, enabled)
    }

    fun updateSpectrumEnabled(enabled: Boolean) {
        spectrumEnabled = enabled
        AppPreferences.setSpectrumEnabled(appContext, enabled)
        syncSpectrumAnalyzer()
    }

    fun togglePlayerImmersiveLower() {
        updatePlayerImmersiveLower(!playerImmersiveLower)
    }

    /** 当前背景下是否使用封面底边进度（仅主题色 / 封面模糊）。 */
    fun useCoverEdgeProgressNow(): Boolean {
        if (!coverEdgeProgress) return false
        return playerLowerBackground == PlayerLowerBackgroundMode.THEME ||
            playerLowerBackground == PlayerLowerBackgroundMode.COVER_GLOW
    }

    @Composable
    fun isDarkTheme(): Boolean = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    private fun syncSpectrumAnalyzer() {
        MicaSpectrumAnalyzer.setEnabled(
            spectrumEnabled || miniPlayerStyle == MiniPlayerStyle.AUDIOPHILE,
        )
    }
}
