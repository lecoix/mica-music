package com.mica.music.ui.screens.settings

import androidx.compose.runtime.Composable
import com.mica.music.data.MusicLibrary
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsSectionTitle

@Composable
internal fun LibraryScanSettingsPanel(
    library: MusicLibrary,
    excludedDirectories: List<String>,
    minDurationSec: Int,
    onChooseLibraryFolder: () -> Unit,
    onRescan: () -> Unit,
    onEditExcludedDirectories: () -> Unit,
    onMinDurationSelected: (Int) -> Unit,
) {
    SettingsSectionTitle("曲库与扫描")

    SettingsActionRow(
        title = "曲库文件夹",
        subtitle = library.libraryFolderLabel?.let { "当前：$it" } ?: "未选择 · 通过系统文件选择器授权目录",
        onClick = onChooseLibraryFolder,
        enabled = !library.isScanning,
    )

    SettingsActionRow(
        title = "重新扫描曲库",
        subtitle = when {
            library.isScanning -> library.scanProgressLabel ?: "扫描中…"
            library.hasLibraryFolder() && !library.hasAudioReadPermission() ->
                "将扫描「${library.libraryFolderLabel}」"
            !library.hasAudioReadPermission() && !library.hasLibraryFolder() ->
                "需要授予读取音频权限，或先选择曲库文件夹"
            library.lastScanAtMs == null -> "尚未扫描"
            else -> "共 ${library.songs.size} 首 · ${library.totalSizeMb} MB"
        },
        onClick = onRescan,
        enabled = !library.isScanning,
    )

    SettingsActionRow(
        title = "排除目录",
        subtitle = if (excludedDirectories.isEmpty()) {
            "未排除目录 · 从已扫描文件夹中选择"
        } else {
            "已排除 ${excludedDirectories.size} 个目录 · 更改后自动重扫"
        },
        onClick = onEditExcludedDirectories,
        enabled = !library.isScanning &&
            (library.songs.isNotEmpty() || excludedDirectories.isNotEmpty()),
    )

    SettingsChoiceRow(
        title = "最短曲目时长",
        subtitle = "过滤铃声、提示音等极短音频",
        choices = DurationChoices,
        selectedValue = minDurationSec,
        onSelect = onMinDurationSelected,
    )
}
