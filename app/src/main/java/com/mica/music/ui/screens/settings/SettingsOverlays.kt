package com.mica.music.ui.screens.settings

import androidx.compose.runtime.Composable
import com.mica.music.data.AppUiSettings
import com.mica.music.data.ArtistSplitConfig
import com.mica.music.data.MusicLibrary
import com.mica.music.data.scanner.scanDirectoryCandidates
import com.mica.music.ui.screens.settings.color.CustomAccentColorDialog
import com.mica.music.ui.screens.settings.color.CustomMicaBackgroundDialog

data class SettingsOverlayState(
    val showCustomAccent: Boolean = false,
    val showCustomMica: Boolean = false,
    val showExcludedDirectories: Boolean = false,
    val showArtistSplit: Boolean = false,
)

@Composable
internal fun SettingsOverlays(
    overlays: SettingsOverlayState,
    uiSettings: AppUiSettings,
    library: MusicLibrary,
    excludedDirectories: List<String>,
    artistSplitConfig: ArtistSplitConfig,
    onDismissCustomAccent: () -> Unit,
    onDismissCustomMica: () -> Unit,
    onDismissExcludedDirectories: () -> Unit,
    onConfirmExcludedDirectories: (List<String>) -> Unit,
    onDismissArtistSplit: () -> Unit,
    onConfirmArtistSplit: (ArtistSplitConfig) -> Unit,
) {
    if (overlays.showCustomAccent) {
        CustomAccentColorDialog(
            initialColorArgb = uiSettings.customAccentColorArgb,
            onDismiss = onDismissCustomAccent,
            onConfirm = { colorArgb ->
                uiSettings.updateCustomAccentColorArgb(colorArgb)
                onDismissCustomAccent()
            },
        )
    }

    if (overlays.showCustomMica) {
        CustomMicaBackgroundDialog(
            initialStartArgb = uiSettings.customMicaStartArgb,
            initialEndArgb = uiSettings.customMicaEndArgb,
            initialSingleColor = uiSettings.customMicaSingleColor,
            onDismiss = onDismissCustomMica,
            onConfirm = { startArgb, endArgb, singleColor ->
                uiSettings.updateCustomMicaBackground(startArgb, endArgb, singleColor)
                onDismissCustomMica()
            },
        )
    }

    if (overlays.showExcludedDirectories) {
        ExcludedDirectoriesDialog(
            excludedDirectories = excludedDirectories,
            candidateDirectories = scanDirectoryCandidates(library.songs),
            isScanning = library.isScanning,
            onConfirm = { directories ->
                onConfirmExcludedDirectories(directories)
                onDismissExcludedDirectories()
            },
            onDismiss = onDismissExcludedDirectories,
        )
    }

    if (overlays.showArtistSplit) {
        ArtistSplitSettingsDialog(
            config = artistSplitConfig,
            onConfirm = { updated ->
                onConfirmArtistSplit(updated)
                onDismissArtistSplit()
            },
            onDismiss = onDismissArtistSplit,
        )
    }
}
