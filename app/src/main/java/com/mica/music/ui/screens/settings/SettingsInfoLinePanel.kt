package com.mica.music.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mica.music.data.AppUiSettings
import com.mica.music.data.BrowseListInfoVisibility
import com.mica.music.data.PlayerInfoVisibility
import com.mica.music.data.SongListInfoVisibility
import com.mica.music.data.SongTrailingInfo
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsTextFieldRow
import com.mica.music.ui.components.SettingsToggleRow
import com.mica.music.ui.theme.HifiSpacing

@Composable
internal fun InfoLineSettingsPanel(uiSettings: AppUiSettings) {
    SettingsSectionTitle("播放页")

    val playerInfo = uiSettings.playerInfoVisibility
    fun updatePlayerInfo(transform: (PlayerInfoVisibility) -> PlayerInfoVisibility) {
        uiSettings.updatePlayerInfoVisibility(transform(uiSettings.playerInfoVisibility))
    }

    SettingsToggleRow(
        title = "格式",
        subtitle = "显示容器格式，如 FLAC、MP3",
        checked = playerInfo.showFormat,
        onCheckedChange = { checked ->
            updatePlayerInfo { it.copy(showFormat = checked) }
        },
    )

    SettingsToggleRow(
        title = "位深/采样率",
        subtitle = "显示如 24bit/96kHz",
        checked = playerInfo.showSampleRate,
        onCheckedChange = { checked ->
            updatePlayerInfo { it.copy(showSampleRate = checked) }
        },
    )

    SettingsToggleRow(
        title = "比特率",
        subtitle = "显示如 320 kbps",
        checked = playerInfo.showBitrate,
        onCheckedChange = { checked ->
            updatePlayerInfo { it.copy(showBitrate = checked) }
        },
    )

    SettingsToggleRow(
        title = "速度",
        subtitle = "显示当前播放速度，如 1.25x",
        checked = playerInfo.showPlaybackSpeed,
        onCheckedChange = { checked ->
            updatePlayerInfo { it.copy(showPlaybackSpeed = checked) }
        },
    )

    SettingsToggleRow(
        title = "音高",
        subtitle = "显示当前变调，如 +2 半音",
        checked = playerInfo.showPlaybackPitch,
        onCheckedChange = { checked ->
            updatePlayerInfo { it.copy(showPlaybackPitch = checked) }
        },
    )

    SettingsToggleRow(
        title = "时间",
        subtitle = "显示当前系统时间",
        checked = playerInfo.showCurrentTime,
        onCheckedChange = { checked ->
            updatePlayerInfo { it.copy(showCurrentTime = checked) }
        },
    )

    SettingsToggleRow(
        title = "自定义",
        subtitle = "在信息行末尾追加自定义文字",
        checked = playerInfo.showCustomText,
        onCheckedChange = { checked ->
            updatePlayerInfo { it.copy(showCustomText = checked) }
        },
    )

    SettingsTextFieldRow(
        value = playerInfo.customText,
        onValueChange = { text ->
            updatePlayerInfo { it.copy(customText = text) }
        },
        placeholder = "输入自定义文本",
        enabled = playerInfo.showCustomText,
    )

    Spacer(Modifier.height(HifiSpacing.lg))

    SettingsSectionTitle("歌曲列表 · 信息栏")

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

    Spacer(Modifier.height(HifiSpacing.lg))
    SettingsSectionTitle("歌曲列表 · 歌曲副行")

    SettingsToggleRow(
        title = "艺术家",
        subtitle = "在歌曲副行显示艺术家",
        checked = songListInfo.showSongArtist,
        onCheckedChange = { checked -> updateSongListInfo { it.copy(showSongArtist = checked) } },
    )

    SettingsToggleRow(
        title = "专辑",
        subtitle = "在歌曲副行显示专辑",
        checked = songListInfo.showSongAlbum,
        onCheckedChange = { checked -> updateSongListInfo { it.copy(showSongAlbum = checked) } },
    )

    SettingsToggleRow(
        title = "播放次数",
        subtitle = "歌曲播放过后在副行显示播放次数",
        checked = songListInfo.showSongPlayCount,
        onCheckedChange = { checked -> updateSongListInfo { it.copy(showSongPlayCount = checked) } },
    )

    SettingsToggleRow(
        title = "歌曲时长",
        subtitle = "在歌曲副行显示歌曲时长，如 3:45",
        checked = songListInfo.showSongDuration,
        onCheckedChange = { checked -> updateSongListInfo { it.copy(showSongDuration = checked) } },
    )

    Spacer(Modifier.height(HifiSpacing.lg))
    SettingsSectionTitle("歌曲列表 · 右侧信息")

    SettingsChoiceRow(
        title = "显示内容",
        subtitle = "选择每首歌曲右侧显示的内容",
        choices = SongTrailingInfo.entries.map { it.storageValue to it.label },
        selectedValue = songListInfo.trailingInfo.storageValue,
        onSelect = { value ->
            updateSongListInfo { it.copy(trailingInfo = SongTrailingInfo.fromStorage(value)) }
        },
    )

    Spacer(Modifier.height(HifiSpacing.lg))
    BrowseListInfoSettings(uiSettings)
}

@Composable
private fun BrowseListInfoSettings(uiSettings: AppUiSettings) {
    val info = uiSettings.browseListInfoVisibility
    fun update(transform: (BrowseListInfoVisibility) -> BrowseListInfoVisibility) {
        uiSettings.updateBrowseListInfoVisibility(transform(uiSettings.browseListInfoVisibility))
    }

    SettingsSectionTitle("艺术家列表")
    SettingsToggleRow("艺术家数量", "显示艺术家总数", info.showArtistCount, onCheckedChange = {
        update { value -> value.copy(showArtistCount = it) }
    })
    SettingsToggleRow("排序方式", "显示当前艺术家列表排序", info.showArtistSortOrder, onCheckedChange = {
        update { value -> value.copy(showArtistSortOrder = it) }
    })
    SettingsToggleRow("列表列数", "显示当前列表列数", info.showArtistGridColumns, onCheckedChange = {
        update { value -> value.copy(showArtistGridColumns = it) }
    })
    SettingsToggleRow("上次扫描时间", "显示距离上次扫描的时间", info.showArtistLastScanTime, onCheckedChange = {
        update { value -> value.copy(showArtistLastScanTime = it) }
    })
    SettingsToggleRow("自定义", "在统计行末尾追加自定义文字", info.showArtistCustomText, onCheckedChange = {
        update { value -> value.copy(showArtistCustomText = it) }
    })
    SettingsTextFieldRow(
        value = info.artistCustomText,
        onValueChange = { text -> update { it.copy(artistCustomText = text) } },
        placeholder = "输入自定义文本",
        enabled = info.showArtistCustomText,
    )

    Spacer(Modifier.height(HifiSpacing.lg))
    SettingsSectionTitle("专辑列表")
    SettingsToggleRow("专辑数量", "显示专辑总数", info.showAlbumCount, onCheckedChange = {
        update { value -> value.copy(showAlbumCount = it) }
    })
    SettingsToggleRow("排序方式", "显示当前专辑列表排序", info.showAlbumSortOrder, onCheckedChange = {
        update { value -> value.copy(showAlbumSortOrder = it) }
    })
    SettingsToggleRow("列表列数", "显示当前列表列数", info.showAlbumGridColumns, onCheckedChange = {
        update { value -> value.copy(showAlbumGridColumns = it) }
    })
    SettingsToggleRow("上次扫描时间", "显示距离上次扫描的时间", info.showAlbumLastScanTime, onCheckedChange = {
        update { value -> value.copy(showAlbumLastScanTime = it) }
    })
    SettingsToggleRow("自定义", "在统计行末尾追加自定义文字", info.showAlbumCustomText, onCheckedChange = {
        update { value -> value.copy(showAlbumCustomText = it) }
    })
    SettingsTextFieldRow(
        value = info.albumCustomText,
        onValueChange = { text -> update { it.copy(albumCustomText = text) } },
        placeholder = "输入自定义文本",
        enabled = info.showAlbumCustomText,
    )
}
