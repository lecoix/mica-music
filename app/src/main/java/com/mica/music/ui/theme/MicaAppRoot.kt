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
    CompositionLocalProvider(MicaMotion.LocalEnabled provides !reduceMotion) {
        MicaTheme(
            darkTheme = darkTheme,
            accentColor = uiSettings.accentColor,
            micaBackgroundPreset = uiSettings.micaBackgroundPreset,
            coverDisplayMode = uiSettings.coverDisplayMode,
            lyricSplitEnabled = uiSettings.lyricSplitEnabled,
        ) {
            StatusBarEffect(
                hideStatusBar = uiSettings.hideStatusBar,
                darkTheme = darkTheme,
            )
            content()
        }
    }
}
