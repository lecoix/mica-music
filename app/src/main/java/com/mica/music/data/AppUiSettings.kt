package com.mica.music.data

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mica.music.media.MicaSpectrumAnalyzer
import com.mica.music.util.DiagnosticLog
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

    var stripSongTitleParentheses by mutableStateOf(AppPreferences.stripSongTitleParentheses(appContext))
        private set

    var miniPlayerStyle by mutableStateOf(AppPreferences.miniPlayerStyle(appContext))
        private set

    var coverDisplayMode by mutableStateOf(AppPreferences.coverDisplayMode(appContext))
        private set

    var playerCoverFlowMode by mutableStateOf(AppPreferences.playerCoverFlowMode(appContext))
        private set

    var particleCoverTuning by mutableStateOf(AppPreferences.particleCoverTuning(appContext))
        private set

    var accentColor by mutableStateOf(AppPreferences.appAccentColor(appContext))
        private set

    var customAccentColorArgb by mutableIntStateOf(AppPreferences.customAccentColorArgb(appContext))
        private set

    var micaBackgroundPreset by mutableStateOf(AppPreferences.micaBackgroundPreset(appContext))
        private set

    var customMicaStartArgb by mutableIntStateOf(AppPreferences.customMicaStartArgb(appContext))
        private set

    var customMicaEndArgb by mutableIntStateOf(AppPreferences.customMicaEndArgb(appContext))
        private set

    var customMicaSingleColor by mutableStateOf(AppPreferences.customMicaSingleColor(appContext))
        private set

    var lyricSplitEnabled by mutableStateOf(AppPreferences.lyricSplitEnabled(appContext))
        private set

    var lyricsBilingualDisplayMode by mutableStateOf(AppPreferences.lyricsBilingualDisplayMode(appContext))
        private set

    var lyricLineFillEnabled by mutableStateOf(AppPreferences.lyricLineFillEnabled(appContext))
        private set

    var lyricsPageAlignment by mutableStateOf(AppPreferences.lyricsPageAlignment(appContext))
        private set

    var lyricsPageFontSizeSp by mutableIntStateOf(AppPreferences.lyricsPageFontSizeSp(appContext))
        private set

    var lyricsPageImmersive by mutableStateOf(AppPreferences.lyricsPageImmersive(appContext))
        private set

    var notificationLyricsEnabled by mutableStateOf(AppPreferences.notificationLyricsEnabled(appContext))
        private set

    var spectrumEnabled by mutableStateOf(AppPreferences.spectrumEnabled(appContext))
        private set

    var songListInfoVisibility by mutableStateOf(AppPreferences.songListInfoVisibility(appContext))
        private set

    var playerInfoVisibility by mutableStateOf(AppPreferences.playerInfoVisibility(appContext))
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

    fun updateStripSongTitleParentheses(enabled: Boolean) {
        stripSongTitleParentheses = enabled
        AppPreferences.setStripSongTitleParentheses(appContext, enabled)
    }

    fun updateMiniPlayerStyle(style: MiniPlayerStyle) {
        miniPlayerStyle = style
        AppPreferences.setMiniPlayerStyle(appContext, style)
        syncSpectrumAnalyzer(notifyPipeline = true)
    }

    fun updateCoverDisplayMode(mode: CoverDisplayMode) {
        coverDisplayMode = mode
        AppPreferences.setCoverDisplayMode(appContext, mode)
    }

    fun updatePlayerCoverFlowMode(mode: PlayerCoverFlowMode) {
        playerCoverFlowMode = mode
        AppPreferences.setPlayerCoverFlowMode(appContext, mode)
        syncSpectrumAnalyzer(notifyPipeline = true)
    }

    fun updateParticleCoverTuning(tuning: ParticleCoverTuning) {
        particleCoverTuning = tuning
        AppPreferences.setParticleCoverTuning(appContext, tuning)
    }

    fun updateAccentColor(accent: AppAccentColor) {
        accentColor = accent
        AppPreferences.setAppAccentColor(appContext, accent)
    }

    fun updateCustomAccentColorArgb(colorArgb: Int) {
        customAccentColorArgb = colorArgb
        AppPreferences.setCustomAccentColorArgb(appContext, colorArgb)
        updateAccentColor(AppAccentColor.CUSTOM)
    }

    fun updateMicaBackgroundPreset(preset: MicaPreset) {
        micaBackgroundPreset = preset
        AppPreferences.setMicaBackgroundPreset(appContext, preset)
    }

    fun updateCustomMicaBackground(startArgb: Int, endArgb: Int, singleColor: Boolean) {
        customMicaStartArgb = startArgb
        customMicaEndArgb = endArgb
        customMicaSingleColor = singleColor
        AppPreferences.setCustomMicaStartArgb(appContext, startArgb)
        AppPreferences.setCustomMicaEndArgb(appContext, endArgb)
        AppPreferences.setCustomMicaSingleColor(appContext, singleColor)
        updateMicaBackgroundPreset(MicaPreset.CUSTOM)
    }

    fun updateLyricSplitEnabled(enabled: Boolean) {
        lyricSplitEnabled = enabled
        AppPreferences.setLyricSplitEnabled(appContext, enabled)
    }

    fun updateLyricsBilingualDisplayMode(mode: LyricsBilingualDisplayMode) {
        lyricsBilingualDisplayMode = mode
        AppPreferences.setLyricsBilingualDisplayMode(appContext, mode)
    }

    fun updateLyricLineFillEnabled(enabled: Boolean) {
        lyricLineFillEnabled = enabled
        AppPreferences.setLyricLineFillEnabled(appContext, enabled)
    }

    fun updateLyricsPageAlignment(alignment: LyricsPageAlignment) {
        lyricsPageAlignment = alignment
        AppPreferences.setLyricsPageAlignment(appContext, alignment)
    }

    fun updateLyricsPageFontSizeSp(fontSizeSp: Int) {
        lyricsPageFontSizeSp = fontSizeSp.coerceIn(
            MIN_LYRICS_PAGE_FONT_SIZE_SP,
            MAX_LYRICS_PAGE_FONT_SIZE_SP,
        )
        AppPreferences.setLyricsPageFontSizeSp(appContext, lyricsPageFontSizeSp)
    }

    fun updateLyricsPageImmersive(enabled: Boolean) {
        lyricsPageImmersive = enabled
        AppPreferences.setLyricsPageImmersive(appContext, enabled)
    }

    fun updateNotificationLyricsEnabled(enabled: Boolean) {
        notificationLyricsEnabled = enabled
        AppPreferences.setNotificationLyricsEnabled(appContext, enabled)
    }

    fun updateSpectrumEnabled(enabled: Boolean) {
        spectrumEnabled = enabled
        AppPreferences.setSpectrumEnabled(appContext, enabled)
        syncSpectrumAnalyzer(notifyPipeline = true)
        DiagnosticLog.event("Spectrum", "setting enabled=$enabled analyzer=${MicaSpectrumAnalyzer.isEnabledForProcessing()}")
    }

    fun updateSongListInfoVisibility(visibility: SongListInfoVisibility) {
        songListInfoVisibility = visibility
        AppPreferences.setSongListInfoVisibility(appContext, visibility)
    }

    fun updatePlayerInfoVisibility(visibility: PlayerInfoVisibility) {
        playerInfoVisibility = visibility
        AppPreferences.setPlayerInfoVisibility(appContext, visibility)
    }

    fun togglePlayerImmersiveLower() {
        updatePlayerImmersiveLower(!playerImmersiveLower)
    }

    fun toggleLyricsPageImmersive() {
        updateLyricsPageImmersive(!lyricsPageImmersive)
    }

    /** 当前背景下是否使用封面底边进度（仅主题色 / 封面模糊）。 */
    fun useCoverEdgeProgressNow(): Boolean {
        if (!coverEdgeProgress) return false
        return playerLowerBackground == PlayerLowerBackgroundMode.THEME ||
            playerLowerBackground.usesBlurredArtwork
    }

    @Composable
    fun isDarkTheme(): Boolean = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    private fun syncSpectrumAnalyzer(notifyPipeline: Boolean = false) {
        MicaSpectrumAnalyzer.setEnabled(
            spectrumEnabled ||
                miniPlayerStyle == MiniPlayerStyle.AUDIOPHILE ||
                playerCoverFlowMode.usesPhotoStack,
            notifyPipeline = notifyPipeline,
        )
    }
}
