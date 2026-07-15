package com.mica.music.ui.screens.settings

import androidx.compose.runtime.Composable
import com.mica.music.data.AppUiSettings
import com.mica.music.data.LyricsSlot
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsDropdownRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsToggleRow

@Composable
internal fun AdvancedSettingsPanel(
    uiSettings: AppUiSettings,
    includeNonMusic: Boolean,
    deepProbe: Boolean,
    hasSongs: Boolean,
    onIncludeNonMusicChange: (Boolean) -> Unit,
    onDeepProbeChange: (Boolean) -> Unit,
    onOpenMetadataDebug: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    SettingsSectionTitle("高级扫描与调试")

    SettingsToggleRow(
        title = "独占音频焦点",
        subtitle = "开启时播放会让其他应用暂停；关闭后允许与其他应用一起播放，下次切歌或开始播放时生效",
        checked = uiSettings.audioFocusEnabled,
        onCheckedChange = { uiSettings.updateAudioFocusEnabled(it) },
    )

    SettingsDropdownRow(
        title = "歌词优先级",
        subtitle = "按顺序选择已扫描的歌词；缺少前一项时自动使用下一项",
        choices = lyricsPriorityChoices.mapIndexed { index, (_, label) -> index to label },
        selectedValue = lyricsPriorityChoices.indexOfFirst { it.first == uiSettings.lyricsSlotPriority }
            .coerceAtLeast(0),
        onSelect = { index ->
            lyricsPriorityChoices.getOrNull(index)?.first?.let(uiSettings::updateLyricsSlotPriority)
        },
    )

    SettingsToggleRow(
        title = "纳入非「音乐」标记的音频",
        subtitle = "开启后可扫描到部分 m4a / ALAC（MediaStore 里 IS_MUSIC=0）",
        checked = includeNonMusic,
        onCheckedChange = onIncludeNonMusicChange,
    )

    SettingsToggleRow(
        title = "深度分析音质与封面",
        subtitle = "我也不知道这玩意还有啥用，总之先别关",
        checked = deepProbe,
        onCheckedChange = onDeepProbeChange,
    )

    SettingsActionRow(
        title = "元数据调试",
        subtitle = "逐首查看应用内字段、ID3/Vorbis、Retriever 与解析器结果",
        onClick = onOpenMetadataDebug,
        enabled = hasSongs,
    )

    SettingsActionRow(
        title = "系统权限与应用信息",
        subtitle = "管理存储/音频读取、通知等权限",
        onClick = onOpenAppSettings,
    )
}

private val lyricsPriorityChoices = listOf(
    listOf(LyricsSlot.EXTERNAL_TTML, LyricsSlot.EXTERNAL_LRC, LyricsSlot.EMBEDDED) to
        "外部 TTML → 外部 LRC → 内嵌",
    listOf(LyricsSlot.EXTERNAL_TTML, LyricsSlot.EMBEDDED, LyricsSlot.EXTERNAL_LRC) to
        "外部 TTML → 内嵌 → 外部 LRC",
    listOf(LyricsSlot.EXTERNAL_LRC, LyricsSlot.EXTERNAL_TTML, LyricsSlot.EMBEDDED) to
        "外部 LRC → 外部 TTML → 内嵌",
    listOf(LyricsSlot.EXTERNAL_LRC, LyricsSlot.EMBEDDED, LyricsSlot.EXTERNAL_TTML) to
        "外部 LRC → 内嵌 → 外部 TTML",
    listOf(LyricsSlot.EMBEDDED, LyricsSlot.EXTERNAL_TTML, LyricsSlot.EXTERNAL_LRC) to
        "内嵌 → 外部 TTML → 外部 LRC",
    listOf(LyricsSlot.EMBEDDED, LyricsSlot.EXTERNAL_LRC, LyricsSlot.EXTERNAL_TTML) to
        "内嵌 → 外部 LRC → 外部 TTML",
)
