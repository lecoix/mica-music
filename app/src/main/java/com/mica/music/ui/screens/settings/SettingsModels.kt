package com.mica.music.ui.screens.settings

import com.mica.music.data.AppAccentColor
import com.mica.music.data.AppFontSource
import com.mica.music.data.AppThemeMode
import com.mica.music.data.CompactLyricsLineMode
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.ExternalLyricsColorMode
import com.mica.music.data.ExternalLyricsMode
import com.mica.music.data.ExternalLyricsVisibilityMode
import com.mica.music.data.DEFAULT_LETTER_SEAL_OPACITY_PERCENT
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsPageAlignment
import com.mica.music.data.LyricsPageTheme
import com.mica.music.data.LyricsSlot
import com.mica.music.data.LyricsWordAnimationPreset
import com.mica.music.data.MAX_LETTER_SEAL_OPACITY_PERCENT
import com.mica.music.data.MAX_LETTER_SEAL_ROTATION_DEGREES
import com.mica.music.data.MAX_LETTER_SEAL_SIZE_DP
import com.mica.music.data.MAX_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.MAX_LYRICS_PAGE_LINE_SPACING_DP
import com.mica.music.data.MIN_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.MIN_LYRICS_PAGE_LINE_SPACING_DP
import com.mica.music.data.MIN_LETTER_SEAL_OPACITY_PERCENT
import com.mica.music.data.MIN_LETTER_SEAL_ROTATION_DEGREES
import com.mica.music.data.MIN_LETTER_SEAL_SIZE_DP
import com.mica.music.data.MiniPlayerSwipeAction
import com.mica.music.data.HiResBadgeStyle
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.data.PlaybackContentColorMode
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.PlaylistSidebarStyle
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

internal val PlaylistSidebarStyleChoices = PlaylistSidebarStyle.entries.map {
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

internal val ExternalLyricsBilingualDisplayChoices = listOf(
    LyricsBilingualDisplayMode.ORIGINAL.ordinal to "\u4EC5\u539F\u6587",
    LyricsBilingualDisplayMode.TRANSLATION.ordinal to "\u4EC5\u8BD1\u6587",
    LyricsBilingualDisplayMode.ALL.ordinal to "\u5168\u6587",
)

internal val ExternalLyricsVisibilityChoices = ExternalLyricsVisibilityMode.entries.map {
    it.ordinal to it.settingsLabel
}

internal val ExternalLyricsModeChoices = ExternalLyricsMode.entries.map {
    it.ordinal to it.settingsLabel
}

internal val ExternalLyricsColorModeChoices = ExternalLyricsColorMode.entries.map {
    it.ordinal to it.settingsLabel
}

internal val ExternalLyricsColorCountChoices = listOf(
    2 to "2 色",
    3 to "3 色",
    4 to "4 色",
)

internal val ExternalLyricsGradientAngleChoices = listOf(
    0 to "0°（左→右）",
    45 to "45°",
    90 to "90°（上→下）",
    135 to "135°",
    180 to "180°（右→左）",
    225 to "225°",
    270 to "270°（下→上）",
    315 to "315°",
    360 to "360°",
)

internal val StatusBarLyricsTopOffsetChoices = listOf(
    0 to "贴顶",
    24 to "下移 24dp",
    48 to "下移 48dp",
    72 to "下移 72dp",
    96 to "下移 96dp",
    128 to "下移 128dp",
    192 to "下移 192dp",
)

internal val ExternalLyricsWidthChoices = listOf(
    60 to "60%",
    75 to "75%",
    90 to "90%",
    100 to "全宽",
)

internal val LyricsPriorityChoices = listOf(
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

internal val LyricsPageFontSizeChoices = (MIN_LYRICS_PAGE_FONT_SIZE_SP..MAX_LYRICS_PAGE_FONT_SIZE_SP)
    .map { it to "$it sp" }

internal val LyricsPageLineSpacingChoices =
    (MIN_LYRICS_PAGE_LINE_SPACING_DP..MAX_LYRICS_PAGE_LINE_SPACING_DP).map { it to "$it dp" }

internal val LetterSealSizeChoices =
    (MIN_LETTER_SEAL_SIZE_DP..MAX_LETTER_SEAL_SIZE_DP step 2).map { it to "$it dp" }

internal val LetterSealOpacityChoices =
    ((MIN_LETTER_SEAL_OPACITY_PERCENT..MAX_LETTER_SEAL_OPACITY_PERCENT step 5).toList() +
        DEFAULT_LETTER_SEAL_OPACITY_PERCENT)
        .distinct()
        .sorted()
        .map { it to "$it%" }

internal val LetterSealRotationChoices =
    (MIN_LETTER_SEAL_ROTATION_DEGREES..MAX_LETTER_SEAL_ROTATION_DEGREES)
        .map { it to if (it > 0) "+$it°" else "$it°" }

internal enum class SettingsCategory(
    val title: String,
    val subtitle: String,
) {
    APPEARANCE(
        title = "外观",
        subtitle = "主题、强调色、云母背景、壁纸、状态栏、迷你播放",
    ),
    PLAYBACK(
        title = "播放页",
        subtitle = "封面、背景、播放页主题、信息行",
    ),
    LYRICS(
        title = "歌词",
        subtitle = "歌词主题、显示、经典列表、字体、歌词输出",
    ),
    LIBRARY(
        title = "曲库与扫描",
        subtitle = "曲库文件夹、重新扫描、过滤与扫描行为",
    ),
    AUDIO(
        title = "音频与设备",
        subtitle = "ReplayGain、音频焦点",
    ),
    DIAGNOSTICS(
        title = "诊断与系统",
        subtitle = "元数据调试、空间音频、系统权限",
    ),
}
