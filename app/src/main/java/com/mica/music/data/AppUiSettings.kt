package com.mica.music.data

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mica.music.data.preferences.AppearancePreferences
import com.mica.music.data.preferences.FontPreferences
import com.mica.music.data.preferences.LyricsPreferences
import com.mica.music.data.preferences.PlaybackUiPreferences
import com.mica.music.util.DiagnosticLog

/**
 * 界面偏好（主题、状态栏、强调色、云母背景等），供 [com.mica.music.MainActivity] 与设置页共享并即时刷新。
 */
class AppUiSettings(context: Context) {

    private val appContext = context.applicationContext

    var themeMode by mutableStateOf(AppearancePreferences.themeMode(appContext))
        private set

    var hideStatusBar by mutableStateOf(AppearancePreferences.hideStatusBar(appContext))
        private set

    var playerLowerBackground by mutableStateOf(PlaybackUiPreferences.playerLowerBackground(appContext))
        private set

    var coverEdgeProgress by mutableStateOf(PlaybackUiPreferences.coverEdgeProgress(appContext))
        private set

    var keepScreenOnWhenPlaying by mutableStateOf(PlaybackUiPreferences.keepScreenOnWhenPlaying(appContext))
        private set

    var playerImmersiveLower by mutableStateOf(PlaybackUiPreferences.playerImmersiveLower(appContext))
        private set

    var stripSongTitleParentheses by mutableStateOf(PlaybackUiPreferences.stripSongTitleParentheses(appContext))
        private set

    var miniPlayerStyle by mutableStateOf(PlaybackUiPreferences.miniPlayerStyle(appContext))
        private set

    var miniPlayerLyricsEnabled by mutableStateOf(PlaybackUiPreferences.miniPlayerLyricsEnabled(appContext))
        private set

    var miniPlayerSwipeEnabled by mutableStateOf(PlaybackUiPreferences.miniPlayerSwipeEnabled(appContext))
        private set

    var miniPlayerLeftSwipeAction by mutableStateOf(PlaybackUiPreferences.miniPlayerLeftSwipeAction(appContext))
        private set

    var miniPlayerRightSwipeAction by mutableStateOf(PlaybackUiPreferences.miniPlayerRightSwipeAction(appContext))
        private set

    var coverDisplayMode by mutableStateOf(PlaybackUiPreferences.coverDisplayMode(appContext))
        private set

    var playerCoverFlowMode by mutableStateOf(PlaybackUiPreferences.playerCoverFlowMode(appContext))
        private set

    var particleCoverTuning by mutableStateOf(PlaybackUiPreferences.particleCoverTuning(appContext))
        private set

    var accentColor by mutableStateOf(AppearancePreferences.appAccentColor(appContext))
        private set

    var customAccentColorArgb by mutableIntStateOf(AppearancePreferences.customAccentColorArgb(appContext))
        private set

    var micaBackgroundPreset by mutableStateOf(AppearancePreferences.micaBackgroundPreset(appContext))
        private set

    var customMicaStartArgb by mutableIntStateOf(AppearancePreferences.customMicaStartArgb(appContext))
        private set

    var customMicaEndArgb by mutableIntStateOf(AppearancePreferences.customMicaEndArgb(appContext))
        private set

    var customMicaSingleColor by mutableStateOf(AppearancePreferences.customMicaSingleColor(appContext))
        private set

    var customWallpaperPath by mutableStateOf(AppearancePreferences.customWallpaperPath(appContext))
        private set

    var globalFont by mutableStateOf(FontPreferences.globalFont(appContext))
        private set

    var lyricFont by mutableStateOf(FontPreferences.lyricFont(appContext))
        private set

    var lyricSplitEnabled by mutableStateOf(LyricsPreferences.lyricSplitEnabled(appContext))
        private set

    var lyricsBilingualDisplayMode by mutableStateOf(LyricsPreferences.lyricsBilingualDisplayMode(appContext))
        private set

    var lyricLineFillEnabled by mutableStateOf(LyricsPreferences.lyricLineFillEnabled(appContext))
        private set

    var playerPageTextColorMode by mutableStateOf(LyricsPreferences.playerPageTextColorMode(appContext))
        private set

    var lyricsPageTextColorMode by mutableStateOf(LyricsPreferences.lyricsPageTextColorMode(appContext))
        private set

    var lyricsPageAlignment by mutableStateOf(LyricsPreferences.lyricsPageAlignment(appContext))
        private set

    var lyricsPageTheme by mutableStateOf(LyricsPreferences.lyricsPageTheme(appContext))
        private set

    var lyricsPageFontSizeSp by mutableIntStateOf(LyricsPreferences.lyricsPageFontSizeSp(appContext))
        private set

    var lyricsPageTranslationFontSizeSp by mutableIntStateOf(
        LyricsPreferences.lyricsPageTranslationFontSizeSp(appContext),
    )
        private set

    var lyricsPageLineSpacingDp by mutableIntStateOf(LyricsPreferences.lyricsPageLineSpacingDp(appContext))
        private set

    var lyricsPageImmersive by mutableStateOf(LyricsPreferences.lyricsPageImmersive(appContext))
        private set

    var notificationLyricsEnabled by mutableStateOf(LyricsPreferences.notificationLyricsEnabled(appContext))
        private set

    var spectrumEnabled by mutableStateOf(PlaybackUiPreferences.spectrumEnabled(appContext))
        private set

    var audioFocusEnabled by mutableStateOf(PlaybackUiPreferences.audioFocusEnabled(appContext))
        private set

    var songListInfoVisibility by mutableStateOf(PlaybackUiPreferences.songListInfoVisibility(appContext))
        private set

    var browseListInfoVisibility by mutableStateOf(PlaybackUiPreferences.browseListInfoVisibility(appContext))
        private set

    var playerInfoVisibility by mutableStateOf(PlaybackUiPreferences.playerInfoVisibility(appContext))
        private set

    fun updateThemeMode(mode: AppThemeMode) {
        themeMode = mode
        AppearancePreferences.setThemeMode(appContext, mode)
    }

    fun updateHideStatusBar(hide: Boolean) {
        hideStatusBar = hide
        AppearancePreferences.setHideStatusBar(appContext, hide)
    }

    fun updatePlayerLowerBackground(mode: PlayerLowerBackgroundMode) {
        playerLowerBackground = mode
        PlaybackUiPreferences.setPlayerLowerBackground(appContext, mode)
    }

    fun updateCoverEdgeProgress(enabled: Boolean) {
        coverEdgeProgress = enabled
        PlaybackUiPreferences.setCoverEdgeProgress(appContext, enabled)
    }

    fun updateKeepScreenOnWhenPlaying(enabled: Boolean) {
        keepScreenOnWhenPlaying = enabled
        PlaybackUiPreferences.setKeepScreenOnWhenPlaying(appContext, enabled)
    }

    fun updatePlayerImmersiveLower(enabled: Boolean) {
        playerImmersiveLower = enabled
        PlaybackUiPreferences.setPlayerImmersiveLower(appContext, enabled)
    }

    fun updateStripSongTitleParentheses(enabled: Boolean) {
        stripSongTitleParentheses = enabled
        PlaybackUiPreferences.setStripSongTitleParentheses(appContext, enabled)
    }

    fun updateMiniPlayerStyle(style: MiniPlayerStyle) {
        miniPlayerStyle = style
        PlaybackUiPreferences.setMiniPlayerStyle(appContext, style)
    }

    fun updateMiniPlayerLyricsEnabled(enabled: Boolean) {
        miniPlayerLyricsEnabled = enabled
        PlaybackUiPreferences.setMiniPlayerLyricsEnabled(appContext, enabled)
    }

    fun updateMiniPlayerSwipeEnabled(enabled: Boolean) {
        miniPlayerSwipeEnabled = enabled
        PlaybackUiPreferences.setMiniPlayerSwipeEnabled(appContext, enabled)
    }

    fun updateMiniPlayerLeftSwipeAction(action: MiniPlayerSwipeAction) {
        miniPlayerLeftSwipeAction = action
        PlaybackUiPreferences.setMiniPlayerLeftSwipeAction(appContext, action)
    }

    fun updateMiniPlayerRightSwipeAction(action: MiniPlayerSwipeAction) {
        miniPlayerRightSwipeAction = action
        PlaybackUiPreferences.setMiniPlayerRightSwipeAction(appContext, action)
    }

    fun updateCoverDisplayMode(mode: CoverDisplayMode) {
        coverDisplayMode = mode
        PlaybackUiPreferences.setCoverDisplayMode(appContext, mode)
    }

    fun updatePlayerCoverFlowMode(mode: PlayerCoverFlowMode) {
        playerCoverFlowMode = mode
        PlaybackUiPreferences.setPlayerCoverFlowMode(appContext, mode)
    }

    fun updateParticleCoverTuning(tuning: ParticleCoverTuning) {
        particleCoverTuning = tuning
        PlaybackUiPreferences.setParticleCoverTuning(appContext, tuning)
    }

    fun updateAccentColor(accent: AppAccentColor) {
        accentColor = accent
        AppearancePreferences.setAppAccentColor(appContext, accent)
    }

    fun updateCustomAccentColorArgb(colorArgb: Int) {
        customAccentColorArgb = colorArgb
        AppearancePreferences.setCustomAccentColorArgb(appContext, colorArgb)
        updateAccentColor(AppAccentColor.CUSTOM)
    }

    fun updateMicaBackgroundPreset(preset: MicaPreset) {
        micaBackgroundPreset = preset
        AppearancePreferences.setMicaBackgroundPreset(appContext, preset)
    }

    fun updateCustomMicaBackground(startArgb: Int, endArgb: Int, singleColor: Boolean) {
        customMicaStartArgb = startArgb
        customMicaEndArgb = endArgb
        customMicaSingleColor = singleColor
        AppearancePreferences.setCustomMicaStartArgb(appContext, startArgb)
        AppearancePreferences.setCustomMicaEndArgb(appContext, endArgb)
        AppearancePreferences.setCustomMicaSingleColor(appContext, singleColor)
        updateMicaBackgroundPreset(MicaPreset.CUSTOM)
    }

    fun updateCustomWallpaperPath(path: String?) {
        customWallpaperPath = path
        AppearancePreferences.setCustomWallpaperPath(appContext, path)
    }

    fun updateGlobalFont(selection: AppFontSelection) {
        globalFont = selection
        FontPreferences.setGlobalFont(appContext, selection)
    }

    fun updateLyricFont(selection: AppFontSelection) {
        lyricFont = selection
        FontPreferences.setLyricFont(appContext, selection)
    }

    fun updateLyricSplitEnabled(enabled: Boolean) {
        lyricSplitEnabled = enabled
        LyricsPreferences.setLyricSplitEnabled(appContext, enabled)
    }

    fun updateLyricsBilingualDisplayMode(mode: LyricsBilingualDisplayMode) {
        lyricsBilingualDisplayMode = mode
        LyricsPreferences.setLyricsBilingualDisplayMode(appContext, mode)
    }

    fun updateLyricLineFillEnabled(enabled: Boolean) {
        lyricLineFillEnabled = enabled
        LyricsPreferences.setLyricLineFillEnabled(appContext, enabled)
    }

    fun updatePlayerPageTextColorMode(mode: PlaybackContentColorMode) {
        playerPageTextColorMode = mode
        LyricsPreferences.setPlayerPageTextColorMode(appContext, mode)
    }

    fun updateLyricsPageTextColorMode(mode: PlaybackContentColorMode) {
        lyricsPageTextColorMode = mode
        LyricsPreferences.setLyricsPageTextColorMode(appContext, mode)
    }

    fun updateLyricsPageAlignment(alignment: LyricsPageAlignment) {
        lyricsPageAlignment = alignment
        LyricsPreferences.setLyricsPageAlignment(appContext, alignment)
    }

    fun updateLyricsPageTheme(theme: LyricsPageTheme) {
        lyricsPageTheme = theme
        LyricsPreferences.setLyricsPageTheme(appContext, theme)
    }

    fun updateLyricsPageFontSizeSp(fontSizeSp: Int) {
        lyricsPageFontSizeSp = fontSizeSp.coerceIn(
            MIN_LYRICS_PAGE_FONT_SIZE_SP,
            MAX_LYRICS_PAGE_FONT_SIZE_SP,
        )
        LyricsPreferences.setLyricsPageFontSizeSp(appContext, lyricsPageFontSizeSp)
    }

    fun updateLyricsPageTranslationFontSizeSp(fontSizeSp: Int) {
        lyricsPageTranslationFontSizeSp = fontSizeSp.coerceIn(
            MIN_LYRICS_PAGE_FONT_SIZE_SP,
            MAX_LYRICS_PAGE_FONT_SIZE_SP,
        )
        LyricsPreferences.setLyricsPageTranslationFontSizeSp(appContext, lyricsPageTranslationFontSizeSp)
    }

    fun updateLyricsPageLineSpacingDp(spacingDp: Int) {
        lyricsPageLineSpacingDp = spacingDp.coerceIn(
            MIN_LYRICS_PAGE_LINE_SPACING_DP,
            MAX_LYRICS_PAGE_LINE_SPACING_DP,
        )
        LyricsPreferences.setLyricsPageLineSpacingDp(appContext, lyricsPageLineSpacingDp)
    }

    fun updateLyricsPageImmersive(enabled: Boolean) {
        lyricsPageImmersive = enabled
        LyricsPreferences.setLyricsPageImmersive(appContext, enabled)
    }

    fun updateNotificationLyricsEnabled(enabled: Boolean) {
        notificationLyricsEnabled = enabled
        LyricsPreferences.setNotificationLyricsEnabled(appContext, enabled)
    }

    fun updateSpectrumEnabled(enabled: Boolean) {
        spectrumEnabled = enabled
        PlaybackUiPreferences.setSpectrumEnabled(appContext, enabled)
        DiagnosticLog.event("Spectrum", "setting enabled=$enabled")
    }

    fun updateAudioFocusEnabled(enabled: Boolean) {
        audioFocusEnabled = enabled
        PlaybackUiPreferences.setAudioFocusEnabled(appContext, enabled)
    }

    fun updateSongListInfoVisibility(visibility: SongListInfoVisibility) {
        songListInfoVisibility = visibility
        PlaybackUiPreferences.setSongListInfoVisibility(appContext, visibility)
    }

    fun updateBrowseListInfoVisibility(visibility: BrowseListInfoVisibility) {
        browseListInfoVisibility = visibility
        PlaybackUiPreferences.setBrowseListInfoVisibility(appContext, visibility)
    }

    fun updatePlayerInfoVisibility(visibility: PlayerInfoVisibility) {
        playerInfoVisibility = visibility
        PlaybackUiPreferences.setPlayerInfoVisibility(appContext, visibility)
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

}
