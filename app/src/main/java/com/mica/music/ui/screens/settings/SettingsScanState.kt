package com.mica.music.ui.screens.settings

import android.content.Context
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.data.scanner.ExcludedScanDirectories

data class SettingsScanState(
    val includeNonMusic: Boolean,
    val deepProbe: Boolean,
    val minDurationSec: Int,
    val excludedDirectories: List<String>,
) {
    fun withIncludeNonMusic(context: Context, enabled: Boolean): SettingsScanState {
        LibraryScanSettings.setIncludeNonMusicAudio(context, enabled)
        return copy(includeNonMusic = enabled)
    }

    fun withDeepProbe(context: Context, enabled: Boolean): SettingsScanState {
        LibraryScanSettings.setDeepMetadataProbe(context, enabled)
        return copy(deepProbe = enabled)
    }

    fun withMinDurationSec(context: Context, seconds: Int): SettingsScanState {
        LibraryScanSettings.setMinTrackDurationSec(context, seconds)
        return copy(minDurationSec = seconds)
    }

    /** 目录未变时返回 `null`。 */
    fun withExcludedDirectories(context: Context, directories: List<String>): SettingsScanState? {
        val normalized = ExcludedScanDirectories.normalizeAll(directories)
        if (normalized == excludedDirectories) return null
        LibraryScanSettings.setExcludedScanDirectories(context, normalized)
        return copy(excludedDirectories = normalized)
    }

    companion object {
        fun initial(context: Context): SettingsScanState = SettingsScanState(
            includeNonMusic = LibraryScanSettings.includeNonMusicAudio(context),
            deepProbe = LibraryScanSettings.deepMetadataProbe(context),
            minDurationSec = LibraryScanSettings.minTrackDurationSec(context),
            excludedDirectories = LibraryScanSettings.excludedScanDirectories(context),
        )
    }
}
