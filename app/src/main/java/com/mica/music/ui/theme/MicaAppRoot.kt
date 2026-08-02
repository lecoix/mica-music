package com.mica.music.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.mica.music.data.AppUiSettings
import com.mica.music.data.PlaybackQueueIconStyle
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberReduceMotion
import com.mica.music.ui.system.StatusBarEffect

val LocalPlaybackQueueIconStyle = staticCompositionLocalOf { PlaybackQueueIconStyle.ORIGINAL }

@Composable
fun MicaAppRoot(
    uiSettings: AppUiSettings,
    wallpaperViewportState: WallpaperViewportState,
    content: @Composable () -> Unit,
) {
    val darkTheme = uiSettings.isDarkTheme()
    val reduceMotion = rememberReduceMotion()
    CompositionLocalProvider(
        MicaMotion.LocalEnabled provides !reduceMotion,
        LocalWallpaperViewportState provides wallpaperViewportState,
        LocalPlaybackQueueIconStyle provides uiSettings.playbackQueueIconStyle,
    ) {
        MicaTheme(
            darkTheme = darkTheme,
            accentColor = uiSettings.accentColor,
            customAccentColorArgb = uiSettings.customAccentColorArgb,
            micaBackgroundPreset = uiSettings.micaBackgroundPreset,
            customMicaBackground = CustomMicaBackground(
                startArgb = uiSettings.customMicaStartArgb,
                endArgb = uiSettings.customMicaEndArgb,
                singleColor = uiSettings.customMicaSingleColor,
            ),
            customWallpaperPath = uiSettings.customWallpaperPath,
            coverDisplayMode = uiSettings.coverDisplayMode,
            lyricSplitEnabled = uiSettings.lyricSplitEnabled,
            lyricLineFillEnabled = uiSettings.lyricLineFillEnabled,
            globalFont = uiSettings.globalFont,
            lyricFont = uiSettings.lyricFont,
        ) {
            StatusBarEffect(
                hideStatusBar = uiSettings.hideStatusBar,
                darkStatusBarIcons = !darkTheme,
            )
            content()
        }
    }
}
