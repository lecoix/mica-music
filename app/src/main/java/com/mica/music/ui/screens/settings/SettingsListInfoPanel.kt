package com.mica.music.ui.screens.settings

import androidx.compose.runtime.Composable
import com.mica.music.data.AppUiSettings
import com.mica.music.data.SongListInfoVisibility
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsTextFieldRow
import com.mica.music.ui.components.SettingsToggleRow

@Composable
internal fun ListInfoSettingsPanel(uiSettings: AppUiSettings) {
    SettingsSectionTitle("歌曲列表信息自定义")

    val songListInfo = uiSettings.songListInfoVisibility
    fun updateSongListInfo(transform: (SongListInfoVisibility) -> SongListInfoVisibility) {
        uiSettings.updateSongListInfoVisibility(transform(uiSettings.songListInfoVisibility))
    }

    SettingsToggleRow(
        title = "歌曲数量",
        subtitle = "显示「N 首歌曲」或未扫描提示",
        checked = songListInfo.showSongCount,
        onCheckedChange = { checked ->
            updateSongListInfo { it.copy(showSongCount = checked) }
        },
    )

    SettingsToggleRow(
        title = "曲库大小",
        subtitle = "显示曲库占用空间",
        checked = songListInfo.showLibrarySize,
        onCheckedChange = { checked ->
            updateSongListInfo { it.copy(showLibrarySize = checked) }
        },
    )

    SettingsToggleRow(
        title = "排序方式",
        subtitle = "显示当前歌曲列表排序",
        checked = songListInfo.showSortOrder,
        onCheckedChange = { checked ->
            updateSongListInfo { it.copy(showSortOrder = checked) }
        },
    )

    SettingsToggleRow(
        title = "上次扫描时间",
        subtitle = "显示距上次扫描的时间；扫描进行中仍会显示进度",
        checked = songListInfo.showLastScanTime,
        onCheckedChange = { checked ->
            updateSongListInfo { it.copy(showLastScanTime = checked) }
        },
    )

    SettingsToggleRow(
        title = "自定义",
        subtitle = "在信息栏末尾追加自定义文字",
        checked = songListInfo.showCustomText,
        onCheckedChange = { checked ->
            updateSongListInfo { it.copy(showCustomText = checked) }
        },
    )

    SettingsTextFieldRow(
        value = songListInfo.customText,
        onValueChange = { text ->
            updateSongListInfo { it.copy(customText = text) }
        },
        placeholder = "输入自定义文本",
        enabled = songListInfo.showCustomText,
    )
}
