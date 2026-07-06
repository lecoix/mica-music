package com.mica.music.ui.screens.settings

import androidx.compose.runtime.Composable
import com.mica.music.data.AppUiSettings
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsPageAlignment
import com.mica.music.data.PlaybackContentColorMode
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsDropdownRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsToggleRow

@Composable
internal fun LyricsSettingsPanel(uiSettings: AppUiSettings) {
    SettingsSectionTitle("歌词页")

    SettingsToggleRow(
        title = "分割双语歌词",
        subtitle = "将含细空格（U+2009 等）或 //、/、| 的行拆成上下两行；关闭后每行 LRC 保持一行",
        checked = uiSettings.lyricSplitEnabled,
        onCheckedChange = { uiSettings.updateLyricSplitEnabled(it) },
    )

    SettingsChoiceRow(
        title = "双语歌词显示",
        subtitle = "仅在分割双语歌词开启、且当前歌词行可拆分时生效",
        choices = LyricsBilingualDisplayChoices,
        selectedValue = uiSettings.lyricsBilingualDisplayMode.ordinal,
        onSelect = { ordinal ->
            uiSettings.updateLyricsBilingualDisplayMode(
                LyricsBilingualDisplayMode.entries[ordinal],
            )
        },
    )

    SettingsToggleRow(
        title = "强制使用逐字歌词样式",
        subtitle = "对没有逐字时间轴的歌词，当前句按本句到下一句的播放进度从左到右填充",
        checked = uiSettings.lyricLineFillEnabled,
        onCheckedChange = { uiSettings.updateLyricLineFillEnabled(it) },
    )

    SettingsChoiceRow(
        title = "歌词颜色",
        subtitle = "自动：随播放页背景与封面取色；浅色/深色：全屏歌词与播放页迷你歌词统一使用该颜色",
        choices = LyricsPageTextColorChoices,
        selectedValue = uiSettings.lyricsPageTextColorMode.ordinal,
        onSelect = { ordinal ->
            uiSettings.updateLyricsPageTextColorMode(
                PlaybackContentColorMode.entries[ordinal],
            )
        },
    )

    SettingsChoiceRow(
        title = "歌词页对齐",
        choices = LyricsPageAlignmentChoices,
        selectedValue = uiSettings.lyricsPageAlignment.ordinal,
        onSelect = { ordinal ->
            uiSettings.updateLyricsPageAlignment(LyricsPageAlignment.entries[ordinal])
        },
    )

    SettingsDropdownRow(
        title = "原歌词字号",
        choices = LyricsPageFontSizeChoices,
        selectedValue = uiSettings.lyricsPageFontSizeSp,
        onSelect = { uiSettings.updateLyricsPageFontSizeSp(it) },
    )

    SettingsDropdownRow(
        title = "翻译歌词字号",
        choices = LyricsPageFontSizeChoices,
        selectedValue = uiSettings.lyricsPageTranslationFontSizeSp,
        onSelect = { uiSettings.updateLyricsPageTranslationFontSizeSp(it) },
    )

    SettingsToggleRow(
        title = "歌词页沉浸模式",
        subtitle = "开启后歌词页隐藏进度条和底部五个按钮；在歌词页长按播放按钮也可切换",
        checked = uiSettings.lyricsPageImmersive,
        onCheckedChange = { uiSettings.updateLyricsPageImmersive(it) },
    )

    SettingsToggleRow(
        title = "通知栏歌词",
        subtitle = "在系统媒体通知主位显示当前歌词，副位显示歌名与歌手",
        checked = uiSettings.notificationLyricsEnabled,
        onCheckedChange = { uiSettings.updateNotificationLyricsEnabled(it) },
    )
}
