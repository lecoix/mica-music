package com.mica.music.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.mica.music.data.AppUiSettings
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberReduceMotion
import com.mica.music.ui.system.StatusBarEffect

@Composable
fun MicaAppRoot(
    uiSettings: AppUiSettings,
    content: @Composable () -> Unit,
) {
    val darkTheme = uiSettings.isDarkTheme()
    val reduceMotion = rememberReduceMotion()
    CompositionLocalProvider(
        MicaMotion.LocalEnabled provides !reduceMotion,
        LocalAppUiSettings provides uiSettings,
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
