package com.mica.music.ui.screens.settings

import androidx.compose.runtime.Composable
import com.mica.music.data.ArtistSplitConfig
import com.mica.music.data.MusicLibrary
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsToggleRow

@Composable
internal fun LibraryScanSettingsPanel(
    library: MusicLibrary,
    excludedDirectories: List<String>,
    minDurationSec: Int,
    deepProbe: Boolean,
    artistSplitConfig: ArtistSplitConfig,
    remoteLibrarySidebarEnabled: Boolean,
    onChooseLibraryFolder: () -> Unit,
    onRescan: () -> Unit,
    onScanAllMusic: () -> Unit,
    onDeepProbeChange: (Boolean) -> Unit,
    onEditExcludedDirectories: () -> Unit,
    onMinDurationSelected: (Int) -> Unit,
    onEditArtistSplit: () -> Unit,
    onRemoteLibrarySidebarEnabledChange: (Boolean) -> Unit,
    onOpenRemoteMusic: () -> Unit,
) {
    SettingsSectionTitle("曲库来源")

    SettingsToggleRow(
        title = "在侧栏显示远程曲库",
        subtitle = "默认关闭；关闭后仍可从此处管理远程来源",
        checked = remoteLibrarySidebarEnabled,
        onCheckedChange = onRemoteLibrarySidebarEnabledChange,
    )

    SettingsActionRow(
        title = "远程曲库",
        subtitle = "支持 Navidrome / OpenSubsonic、WebDAV、SMB · 每个来源独立同步",
        onClick = onOpenRemoteMusic,
        enabled = !library.isScanning,
    )

    SettingsSectionTitle("本地曲库与扫描")

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
        title = "扫描全部音乐",
        subtitle = "扫描本机全部音频 · 需要读取音频权限",
        onClick = onScanAllMusic,
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

    SettingsSectionTitle("扫描行为")

    SettingsToggleRow(
        title = "深度分析音质与封面",
        subtitle = "读取更完整的音频与封面信息，扫描时间和耗电会增加",
        checked = deepProbe,
        onCheckedChange = onDeepProbeChange,
    )

    SettingsSectionTitle("艺术家")

    SettingsActionRow(
        title = "艺术家分割",
        subtitle = "已启用 ${artistSplitConfig.enabledSeparators.size} 项 · " +
            "白名单 ${artistSplitConfig.whitelist.size} 位",
        onClick = onEditArtistSplit,
    )
}
