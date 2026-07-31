package com.mica.music.ui.screens.settings

import com.mica.music.data.AppAccentColor
import com.mica.music.data.AppFontSource
import com.mica.music.data.AppThemeMode
import com.mica.music.data.CompactLyricsLineMode
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsPageAlignment
import com.mica.music.data.LyricsPageTheme
import com.mica.music.data.LyricsWordAnimationPreset
import com.mica.music.data.MAX_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.MAX_LYRICS_PAGE_LINE_SPACING_DP
import com.mica.music.data.MIN_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.MIN_LYRICS_PAGE_LINE_SPACING_DP
import com.mica.music.data.MiniPlayerSwipeAction
import com.mica.music.data.HiResBadgeStyle
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.data.PlaybackContentColorMode
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.ReplayGainMode
import com.mica.music.ui.theme.MicaPreset

internal val DurationChoices = listOf(
    0 to "不限",
    15 to "≥15秒",
    30 to "≥30秒",
    60 to "≥1分",
    120 to "≥2分",
)

internal val ThemeChoices = listOf(
    AppThemeMode.SYSTEM.ordinal to "跟随系统",
    AppThemeMode.LIGHT.ordinal to "浅色",
    AppThemeMode.DARK.ordinal to "深色",
)

internal val FontSourceChoices = listOf(
    AppFontSource.SYSTEM.ordinal to AppFontSource.SYSTEM.settingsLabel,
)

internal val PlayerLowerBgChoices = PlayerLowerBackgroundMode.entries
    .filterNot { it == PlayerLowerBackgroundMode.DYNAMIC_LIGHT }
    .map { it.ordinal to it.settingsLabel }

internal val MiniPlayerStyleChoices = MiniPlayerStyle.entries.map {
    it.ordinal to it.settingsLabel
}

internal val HiResBadgeStyleChoices = HiResBadgeStyle.entries.map {
    it.ordinal to it.settingsLabel
}

internal val MiniPlayerSwipeActionChoices = MiniPlayerSwipeAction.entries.map {
    it.ordinal to it.settingsLabel
}

internal val AccentColorChoices = AppAccentColor.entries.map {
    it.ordinal to it.settingsLabel
}

internal val MicaBackgroundChoices = MicaPreset.entries.map {
    it.ordinal to it.settingsLabel
}

internal val CoverDisplayChoices = CoverDisplayMode.entries.map {
    it.ordinal to it.settingsLabel
}

internal val PlayerCoverFlowChoices = PlayerCoverFlowMode.entries.map {
    it.ordinal to it.settingsLabel
}

internal val CompactLyricsLineModeChoices = CompactLyricsLineMode.entries.map {
    it.ordinal to it.settingsLabel
}

internal val PlaybackContentColorChoices = PlaybackContentColorMode.entries.map {
    it.ordinal to it.settingsLabel
}

internal val ReplayGainChoices = listOf(
    ReplayGainMode.OFF.ordinal to "关闭",
    ReplayGainMode.TRACK.ordinal to "按曲目",
    ReplayGainMode.ALBUM.ordinal to "按专辑",
)

internal val LyricsPageTextColorChoices = PlaybackContentColorChoices

internal val LyricsPageAlignmentChoices = LyricsPageAlignment.entries.map {
    it.ordinal to it.settingsLabel
}

internal val LyricsPageThemeChoices = LyricsPageTheme.entries.map {
    it.ordinal to it.settingsLabel
}

internal val LyricsWordAnimationPresetChoices = LyricsWordAnimationPreset.entries.map {
    it.ordinal to it.settingsLabel
}

internal val LyricsBilingualDisplayChoices = LyricsBilingualDisplayMode.entries.map {
    it.ordinal to it.settingsLabel
}

internal val LyricsPageFontSizeChoices = (MIN_LYRICS_PAGE_FONT_SIZE_SP..MAX_LYRICS_PAGE_FONT_SIZE_SP)
    .map { it to "$it sp" }

internal val LyricsPageLineSpacingChoices =
    (MIN_LYRICS_PAGE_LINE_SPACING_DP..MAX_LYRICS_PAGE_LINE_SPACING_DP).map { it to "$it dp" }

internal enum class SettingsCategory(
    val title: String,
    val subtitle: String,
) {
    APPEARANCE(
        title = "外观与主题",
        subtitle = "主题、强调色、云母背景、状态栏、迷你播放栏",
    ),
    PLAYBACK(
        title = "播放页",
        subtitle = "封面、播放页背景/UI 颜色、特殊主题、频谱",
    ),
    LYRICS(
        title = "歌词页",
        subtitle = "双语拆分、逐字填充、歌词颜色与样式",
    ),
    LIBRARY(
        title = "曲库与扫描",
        subtitle = "曲库文件夹、重新扫描、时长过滤",
    ),
    ADVANCED(
        title = "高级与调试",
        subtitle = "扫描兼容、元数据调试、系统权限",
    ),
}
