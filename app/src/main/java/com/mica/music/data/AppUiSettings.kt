package com.mica.music.data

import android.content.Context
import android.net.Uri
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
import com.mica.music.data.scanner.VideoCoverPosterPrefetcher
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

    var compactLyricsLineMode by mutableStateOf(PlaybackUiPreferences.compactLyricsLineMode(appContext))
        private set

    var stripSongTitleParentheses by mutableStateOf(PlaybackUiPreferences.stripSongTitleParentheses(appContext))
        private set

    var miniPlayerStyle by mutableStateOf(PlaybackUiPreferences.miniPlayerStyle(appContext))
        private set

    var miniPlayerLyricsEnabled by mutableStateOf(PlaybackUiPreferences.miniPlayerLyricsEnabled(appContext))
        private set

    var miniPlayerWordLyricsEnabled by mutableStateOf(PlaybackUiPreferences.miniPlayerWordLyricsEnabled(appContext))
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

    var videoAlbumCoverEnabled by mutableStateOf(PlaybackUiPreferences.videoAlbumCoverEnabled(appContext))
        private set

    var customPlayerLowerLayout by mutableStateOf(PlaybackUiPreferences.customPlayerLowerLayout(appContext))
        private set

    var customStandardCoverTapPlayPause by mutableStateOf(
        PlaybackUiPreferences.customStandardCoverTapPlayPause(appContext),
    )
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

    var customWallpaperPath by mutableStateOf(loadValidCustomWallpaperPath())
        private set

    var customWallpaperOverlayPercent by mutableIntStateOf(
        AppearancePreferences.customWallpaperOverlayPercent(appContext),
    )
        private set

    var customWallpaperBlurDp by mutableIntStateOf(
        AppearancePreferences.customWallpaperBlurDp(appContext),
    )
        private set

    var customWallpaperCrop by mutableStateOf(AppearancePreferences.customWallpaperCrop(appContext))
        private set

    var pendingCustomWallpaperPath by mutableStateOf<String?>(null)
        private set

    private var pendingCustomWallpaper: AppWallpaperStore.PreparedWallpaper? = null

    private val wallpaperStore by lazy {
        AppWallpaperStore(
            directory = AppWallpaperImporter.wallpaperDirectory(appContext),
            publishPath = { path -> publishCustomWallpaperPath(path, CustomWallpaperCrop.Default) },
        )
    }

    var playlistSidebarStyle by mutableStateOf(
        AppearancePreferences.playlistSidebarStyle(appContext),
    )
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

    var letterSealCustomImagePath by mutableStateOf(
        LyricsPreferences.letterSealCustomImagePath(appContext),
    )
        private set

    var letterSealSizeDp by mutableIntStateOf(LyricsPreferences.letterSealSizeDp(appContext))
        private set

    var letterSealOpacityPercent by mutableIntStateOf(
        LyricsPreferences.letterSealOpacityPercent(appContext),
    )
        private set

    var letterSealRotationDegrees by mutableIntStateOf(
        LyricsPreferences.letterSealRotationDegrees(appContext),
    )
        private set

    var lyricsWordAnimationPreset by mutableStateOf(LyricsPreferences.lyricsWordAnimationPreset(appContext))
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

    var desktopLyricsEnabled by mutableStateOf(LyricsPreferences.desktopLyricsEnabled(appContext))
        private set

    var externalLyricsMode by mutableStateOf(LyricsPreferences.externalLyricsMode(appContext))
        private set

    var desktopLyricsOriginalFontSizeSp by mutableIntStateOf(
        LyricsPreferences.desktopLyricsOriginalFontSizeSp(appContext),
    )
        private set

    var desktopLyricsTranslationFontSizeSp by mutableIntStateOf(
        LyricsPreferences.desktopLyricsTranslationFontSizeSp(appContext),
    )
        private set

    var desktopLyricsBilingualDisplayMode by mutableStateOf(
        LyricsPreferences.desktopLyricsBilingualDisplayMode(appContext),
    )
        private set

    var desktopLyricsWordByWordEnabled by mutableStateOf(
        LyricsPreferences.desktopLyricsWordByWordEnabled(appContext),
    )
        private set

    var desktopLyricsWidthPercent by mutableIntStateOf(
        LyricsPreferences.desktopLyricsWidthPercent(appContext),
    )
        private set

    var statusBarLyricsEnabled by mutableStateOf(LyricsPreferences.statusBarLyricsEnabled(appContext))
        private set

    var statusBarLyricsTopOffsetDp by mutableIntStateOf(
        LyricsPreferences.statusBarLyricsTopOffsetDp(appContext),
    )
        private set

    var statusBarLyricsOriginalFontSizeSp by mutableIntStateOf(
        LyricsPreferences.statusBarLyricsOriginalFontSizeSp(appContext),
    )
        private set

    var statusBarLyricsTranslationFontSizeSp by mutableIntStateOf(
        LyricsPreferences.statusBarLyricsTranslationFontSizeSp(appContext),
    )
        private set

    var statusBarLyricsSplitEnabled by mutableStateOf(
        LyricsPreferences.statusBarLyricsSplitEnabled(appContext),
    )
        private set

    var statusBarLyricsBilingualDisplayMode by mutableStateOf(
        LyricsPreferences.statusBarLyricsBilingualDisplayMode(appContext),
    )
        private set

    var statusBarLyricsWordByWordEnabled by mutableStateOf(
        LyricsPreferences.statusBarLyricsWordByWordEnabled(appContext),
    )
        private set

    var statusBarLyricsTextAlignment by mutableStateOf(
        LyricsPreferences.statusBarLyricsTextAlignment(appContext),
    )
        private set

    var statusBarLyricsWidthPercent by mutableIntStateOf(
        LyricsPreferences.statusBarLyricsWidthPercent(appContext),
    )
        private set

    var externalLyricsVisibilityMode by mutableStateOf(
        LyricsPreferences.externalLyricsVisibilityMode(appContext),
    )
        private set

    var externalLyricsColorMode by mutableStateOf(LyricsPreferences.externalLyricsColorMode(appContext))
        private set

    var externalLyricsColorCount by mutableIntStateOf(LyricsPreferences.externalLyricsColorCount(appContext))
        private set

    var externalLyricsGradientAngleDegrees by mutableIntStateOf(
        LyricsPreferences.externalLyricsGradientAngleDegrees(appContext),
    )
        private set

    var externalLyricsColors by mutableStateOf(LyricsPreferences.externalLyricsColors(appContext))
        private set

    var infoRowLyricsEnabled by mutableStateOf(LyricsPreferences.infoRowLyricsEnabled(appContext))
        private set

    var infoRowWordLyricsEnabled by mutableStateOf(LyricsPreferences.infoRowWordLyricsEnabled(appContext))
        private set

    var lyricsSlotPriority by mutableStateOf(LyricsPreferences.lyricsSlotPriority(appContext))
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

    var hiResBadgeStyle by mutableStateOf(PlaybackUiPreferences.hiResBadgeStyle(appContext))
        private set

    var hiResBadgeCustomImagePath by mutableStateOf(PlaybackUiPreferences.hiResBadgeCustomImagePath(appContext))
        private set

    val hiResBadgeAppearance: HiResBadgeAppearance
        get() = HiResBadgeAppearance(
            style = hiResBadgeStyle,
            customImagePath = hiResBadgeCustomImagePath,
        )

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

    fun updateCompactLyricsLineMode(mode: CompactLyricsLineMode) {
        compactLyricsLineMode = mode
        PlaybackUiPreferences.setCompactLyricsLineMode(appContext, mode)
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

    fun updateMiniPlayerWordLyricsEnabled(enabled: Boolean) {
        miniPlayerWordLyricsEnabled = enabled
        PlaybackUiPreferences.setMiniPlayerWordLyricsEnabled(appContext, enabled)
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

    fun updateVideoAlbumCoverEnabled(enabled: Boolean) {
        videoAlbumCoverEnabled = enabled
        PlaybackUiPreferences.setVideoAlbumCoverEnabled(appContext, enabled)
        if (!enabled) VideoCoverPosterPrefetcher.cancel()
    }

    fun updateCustomPlayerLowerLayout(config: PlayerLowerLayoutConfig) {
        customPlayerLowerLayout = config.normalized()
        PlaybackUiPreferences.setCustomPlayerLowerLayout(appContext, customPlayerLowerLayout)
    }

    fun updateCustomStandardCoverTapPlayPause(enabled: Boolean) {
        customStandardCoverTapPlayPause = enabled
        PlaybackUiPreferences.setCustomStandardCoverTapPlayPause(appContext, enabled)
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

    suspend fun prepareCustomWallpaper(uri: Uri): AppWallpaperImporter.ImportResult {
        pendingCustomWallpaper?.let { wallpaperStore.discard(it) }
        pendingCustomWallpaper = null
        pendingCustomWallpaperPath = null
        val outcome = wallpaperStore.prepare { candidate ->
            AppWallpaperImporter.writeCandidate(appContext, uri, candidate)
        }
        return when (outcome.result) {
            AppWallpaperStore.ReplaceResult.PREPARED -> {
                val prepared = requireNotNull(outcome.wallpaper)
                pendingCustomWallpaper = prepared
                pendingCustomWallpaperPath = prepared.previewPath
                AppWallpaperImporter.ImportResult(true, "")
            }
            AppWallpaperStore.ReplaceResult.SUPERSEDED -> {
                AppWallpaperImporter.ImportResult(false, "")
            }
            AppWallpaperStore.ReplaceResult.PREPARE_FAILED -> {
                AppWallpaperImporter.ImportResult(false, "无法读取或保存壁纸图片")
            }
            AppWallpaperStore.ReplaceResult.COMMIT_FAILED -> {
                AppWallpaperImporter.ImportResult(false, "壁纸图片无法提交")
            }
            AppWallpaperStore.ReplaceResult.APPLIED -> {
                error("prepare must not return APPLIED")
            }
        }
    }

    suspend fun applyPendingCustomWallpaper(
        crop: CustomWallpaperCrop,
        expectedPath: String? = pendingCustomWallpaperPath,
    ): Boolean {
        val prepared = pendingCustomWallpaper ?: return false
        if (prepared.previewPath != expectedPath) return false
        val normalizedCrop = crop.clamped()
        val result = wallpaperStore.commit(prepared) { path ->
            publishCustomWallpaperPath(path, normalizedCrop)
        }
        if (pendingCustomWallpaper === prepared) {
            pendingCustomWallpaper = null
            pendingCustomWallpaperPath = null
        }
        return result == AppWallpaperStore.ReplaceResult.APPLIED
    }

    suspend fun cancelPendingCustomWallpaper(expectedPath: String? = pendingCustomWallpaperPath) {
        val prepared = pendingCustomWallpaper ?: return
        if (prepared.previewPath != expectedPath) return
        pendingCustomWallpaper = null
        pendingCustomWallpaperPath = null
        wallpaperStore.discard(prepared)
    }

    suspend fun clearCustomWallpaper() {
        pendingCustomWallpaper = null
        pendingCustomWallpaperPath = null
        wallpaperStore.clear()
    }

    private fun publishCustomWallpaperPath(path: String?, crop: CustomWallpaperCrop) {
        customWallpaperCrop = crop.clamped()
        AppearancePreferences.setCustomWallpaperCrop(appContext, customWallpaperCrop)
        customWallpaperPath = path
        AppearancePreferences.setCustomWallpaperPath(appContext, path)
    }

    private fun loadValidCustomWallpaperPath(): String? {
        val storedPath = AppearancePreferences.customWallpaperPath(appContext)
        val validPath = AppWallpaperImporter.validStoredPath(storedPath)
        if (storedPath != null && validPath == null) {
            AppearancePreferences.setCustomWallpaperPath(appContext, null)
        }
        return validPath
    }

    fun updateCustomWallpaperOverlayPercent(percent: Int) {
        customWallpaperOverlayPercent = percent.coerceIn(
            MIN_CUSTOM_WALLPAPER_OVERLAY_PERCENT,
            MAX_CUSTOM_WALLPAPER_OVERLAY_PERCENT,
        )
        AppearancePreferences.setCustomWallpaperOverlayPercent(appContext, customWallpaperOverlayPercent)
    }

    fun updateCustomWallpaperBlurDp(blurDp: Int) {
        customWallpaperBlurDp = blurDp.coerceIn(MIN_CUSTOM_WALLPAPER_BLUR_DP, MAX_CUSTOM_WALLPAPER_BLUR_DP)
        AppearancePreferences.setCustomWallpaperBlurDp(appContext, customWallpaperBlurDp)
    }

    fun updateCustomWallpaperCrop(crop: CustomWallpaperCrop) {
        customWallpaperCrop = crop.clamped()
        AppearancePreferences.setCustomWallpaperCrop(appContext, customWallpaperCrop)
    }

    fun updatePlaylistSidebarStyle(style: PlaylistSidebarStyle) {
        playlistSidebarStyle = style
        AppearancePreferences.setPlaylistSidebarStyle(appContext, style)
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

    fun updateLetterSealCustomImagePath(path: String?) {
        letterSealCustomImagePath = path
        LyricsPreferences.setLetterSealCustomImagePath(appContext, path)
    }

    fun updateLetterSealSizeDp(sizeDp: Int) {
        letterSealSizeDp = sizeDp.coerceIn(MIN_LETTER_SEAL_SIZE_DP, MAX_LETTER_SEAL_SIZE_DP)
        LyricsPreferences.setLetterSealSizeDp(appContext, letterSealSizeDp)
    }

    fun updateLetterSealOpacityPercent(opacityPercent: Int) {
        letterSealOpacityPercent = opacityPercent.coerceIn(
            MIN_LETTER_SEAL_OPACITY_PERCENT,
            MAX_LETTER_SEAL_OPACITY_PERCENT,
        )
        LyricsPreferences.setLetterSealOpacityPercent(appContext, letterSealOpacityPercent)
    }

    fun updateLetterSealRotationDegrees(rotationDegrees: Int) {
        letterSealRotationDegrees = rotationDegrees.coerceIn(
            MIN_LETTER_SEAL_ROTATION_DEGREES,
            MAX_LETTER_SEAL_ROTATION_DEGREES,
        )
        LyricsPreferences.setLetterSealRotationDegrees(appContext, letterSealRotationDegrees)
    }

    fun updateLyricsWordAnimationPreset(preset: LyricsWordAnimationPreset) {
        lyricsWordAnimationPreset = preset
        LyricsPreferences.setLyricsWordAnimationPreset(appContext, preset)
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

    fun updateDesktopLyricsEnabled(enabled: Boolean) {
        desktopLyricsEnabled = enabled
        LyricsPreferences.setDesktopLyricsEnabled(appContext, enabled)
        externalLyricsMode = LyricsPreferences.externalLyricsMode(appContext)
    }

    fun updateExternalLyricsMode(mode: ExternalLyricsMode) {
        externalLyricsMode = mode
        LyricsPreferences.setExternalLyricsMode(appContext, mode)
        desktopLyricsEnabled = mode == ExternalLyricsMode.DESKTOP
        statusBarLyricsEnabled = mode == ExternalLyricsMode.STATUS_BAR
    }

    fun updateDesktopLyricsOriginalFontSizeSp(fontSizeSp: Int) {
        desktopLyricsOriginalFontSizeSp = fontSizeSp.coerceIn(
            MIN_LYRICS_PAGE_FONT_SIZE_SP,
            MAX_LYRICS_PAGE_FONT_SIZE_SP,
        )
        LyricsPreferences.setDesktopLyricsOriginalFontSizeSp(appContext, desktopLyricsOriginalFontSizeSp)
    }

    fun updateDesktopLyricsTranslationFontSizeSp(fontSizeSp: Int) {
        desktopLyricsTranslationFontSizeSp = fontSizeSp.coerceIn(
            MIN_LYRICS_PAGE_FONT_SIZE_SP,
            MAX_LYRICS_PAGE_FONT_SIZE_SP,
        )
        LyricsPreferences.setDesktopLyricsTranslationFontSizeSp(
            appContext,
            desktopLyricsTranslationFontSizeSp,
        )
    }

    fun updateDesktopLyricsBilingualDisplayMode(mode: LyricsBilingualDisplayMode) {
        desktopLyricsBilingualDisplayMode = mode
        LyricsPreferences.setDesktopLyricsBilingualDisplayMode(appContext, mode)
    }

    fun updateDesktopLyricsWordByWordEnabled(enabled: Boolean) {
        desktopLyricsWordByWordEnabled = enabled
        LyricsPreferences.setDesktopLyricsWordByWordEnabled(appContext, enabled)
    }

    fun updateDesktopLyricsWidthPercent(percent: Int) {
        desktopLyricsWidthPercent = percent.coerceIn(
            MIN_EXTERNAL_LYRICS_WIDTH_PERCENT,
            MAX_EXTERNAL_LYRICS_WIDTH_PERCENT,
        )
        LyricsPreferences.setDesktopLyricsWidthPercent(appContext, desktopLyricsWidthPercent)
    }

    fun updateStatusBarLyricsEnabled(enabled: Boolean) {
        statusBarLyricsEnabled = enabled
        LyricsPreferences.setStatusBarLyricsEnabled(appContext, enabled)
        externalLyricsMode = LyricsPreferences.externalLyricsMode(appContext)
    }

    fun updateStatusBarLyricsTopOffsetDp(offsetDp: Int) {
        statusBarLyricsTopOffsetDp = offsetDp.coerceIn(
            MIN_STATUS_BAR_LYRICS_TOP_OFFSET_DP,
            MAX_STATUS_BAR_LYRICS_TOP_OFFSET_DP,
        )
        LyricsPreferences.setStatusBarLyricsTopOffsetDp(appContext, statusBarLyricsTopOffsetDp)
    }

    fun updateStatusBarLyricsOriginalFontSizeSp(fontSizeSp: Int) {
        statusBarLyricsOriginalFontSizeSp = fontSizeSp.coerceIn(
            MIN_LYRICS_PAGE_FONT_SIZE_SP,
            MAX_LYRICS_PAGE_FONT_SIZE_SP,
        )
        LyricsPreferences.setStatusBarLyricsOriginalFontSizeSp(
            appContext,
            statusBarLyricsOriginalFontSizeSp,
        )
    }

    fun updateStatusBarLyricsTranslationFontSizeSp(fontSizeSp: Int) {
        statusBarLyricsTranslationFontSizeSp = fontSizeSp.coerceIn(
            MIN_LYRICS_PAGE_FONT_SIZE_SP,
            MAX_LYRICS_PAGE_FONT_SIZE_SP,
        )
        LyricsPreferences.setStatusBarLyricsTranslationFontSizeSp(
            appContext,
            statusBarLyricsTranslationFontSizeSp,
        )
    }

    fun updateStatusBarLyricsSplitEnabled(enabled: Boolean) {
        statusBarLyricsSplitEnabled = enabled
        LyricsPreferences.setStatusBarLyricsSplitEnabled(appContext, enabled)
    }

    fun updateStatusBarLyricsBilingualDisplayMode(mode: LyricsBilingualDisplayMode) {
        statusBarLyricsBilingualDisplayMode = mode
        LyricsPreferences.setStatusBarLyricsBilingualDisplayMode(appContext, mode)
    }

    fun updateStatusBarLyricsWordByWordEnabled(enabled: Boolean) {
        statusBarLyricsWordByWordEnabled = enabled
        LyricsPreferences.setStatusBarLyricsWordByWordEnabled(appContext, enabled)
    }

    fun updateStatusBarLyricsTextAlignment(alignment: LyricsPageAlignment) {
        statusBarLyricsTextAlignment = alignment
        LyricsPreferences.setStatusBarLyricsTextAlignment(appContext, alignment)
    }

    fun updateStatusBarLyricsWidthPercent(percent: Int) {
        statusBarLyricsWidthPercent = percent.coerceIn(
            MIN_EXTERNAL_LYRICS_WIDTH_PERCENT,
            MAX_EXTERNAL_LYRICS_WIDTH_PERCENT,
        )
        LyricsPreferences.setStatusBarLyricsWidthPercent(appContext, statusBarLyricsWidthPercent)
    }

    fun updateExternalLyricsVisibilityMode(mode: ExternalLyricsVisibilityMode) {
        externalLyricsVisibilityMode = mode
        LyricsPreferences.setExternalLyricsVisibilityMode(appContext, mode)
    }

    fun updateExternalLyricsColorMode(mode: ExternalLyricsColorMode) {
        externalLyricsColorMode = mode
        LyricsPreferences.setExternalLyricsColorMode(appContext, mode)
        if (mode == ExternalLyricsColorMode.GRADIENT && externalLyricsColorCount < 2) {
            externalLyricsColorCount = 2
            LyricsPreferences.setExternalLyricsColorCount(appContext, 2)
            externalLyricsColors = LyricsPreferences.externalLyricsColors(appContext)
        }
    }

    fun updateExternalLyricsColorCount(count: Int) {
        externalLyricsColorCount = count.coerceIn(1, MAX_EXTERNAL_LYRICS_COLORS)
        LyricsPreferences.setExternalLyricsColorCount(appContext, externalLyricsColorCount)
        externalLyricsColors = LyricsPreferences.externalLyricsColors(appContext)
    }

    fun updateExternalLyricsGradientAngleDegrees(angleDegrees: Int) {
        externalLyricsGradientAngleDegrees = angleDegrees.coerceIn(0, 360)
        LyricsPreferences.setExternalLyricsGradientAngleDegrees(
            appContext,
            externalLyricsGradientAngleDegrees,
        )
    }

    fun updateExternalLyricsColors(colors: List<Int>) {
        externalLyricsColors = normalizeExternalLyricsColors(colors)
        LyricsPreferences.setExternalLyricsColors(appContext, externalLyricsColors)
    }

    fun updateInfoRowLyricsEnabled(enabled: Boolean) {
        infoRowLyricsEnabled = enabled
        LyricsPreferences.setInfoRowLyricsEnabled(appContext, enabled)
    }

    fun updateInfoRowWordLyricsEnabled(enabled: Boolean) {
        infoRowWordLyricsEnabled = enabled
        LyricsPreferences.setInfoRowWordLyricsEnabled(appContext, enabled)
    }

    fun updateLyricsSlotPriority(priority: List<LyricsSlot>) {
        lyricsSlotPriority = priority
        LyricsPreferences.setLyricsSlotPriority(appContext, priority)
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

    fun updateHiResBadgeStyle(style: HiResBadgeStyle) {
        hiResBadgeStyle = style
        PlaybackUiPreferences.setHiResBadgeStyle(appContext, style)
    }

    fun updateHiResBadgeCustomImagePath(path: String?) {
        hiResBadgeCustomImagePath = path
        PlaybackUiPreferences.setHiResBadgeCustomImagePath(appContext, path)
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
