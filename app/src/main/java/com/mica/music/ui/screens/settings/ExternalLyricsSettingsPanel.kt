package com.mica.music.ui.screens.settings

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mica.music.data.AppUiSettings
import com.mica.music.data.ExternalLyricsColorMode
import com.mica.music.data.ExternalLyricsMode
import com.mica.music.data.ExternalLyricsVisibilityMode
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsPageAlignment
import com.mica.music.data.MAX_EXTERNAL_LYRICS_EFFECT_PERCENT
import com.mica.music.data.MAX_EXTERNAL_LYRICS_WIDTH_PERCENT
import com.mica.music.data.MAX_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.MAX_STATUS_BAR_LYRICS_HORIZONTAL_OFFSET_DP
import com.mica.music.data.MAX_STATUS_BAR_LYRICS_TOP_OFFSET_DP
import com.mica.music.data.MIN_EXTERNAL_LYRICS_EFFECT_PERCENT
import com.mica.music.data.MIN_EXTERNAL_LYRICS_WIDTH_PERCENT
import com.mica.music.data.MIN_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.MIN_STATUS_BAR_LYRICS_HORIZONTAL_OFFSET_DP
import com.mica.music.data.MIN_STATUS_BAR_LYRICS_TOP_OFFSET_DP
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsDropdownRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsSliderRow
import com.mica.music.ui.components.SettingsToggleRow
import com.mica.music.ui.overlay.AndroidExternalLyricsOverlayControl
import com.mica.music.ui.screens.settings.color.ExternalLyricsColorDialog

@Composable
internal fun ExternalLyricsSettingsPanel(
    uiSettings: AppUiSettings,
) {
    val context = LocalContext.current
    val overlayControl = remember(context) { AndroidExternalLyricsOverlayControl(context) }
    var showExternalLyricsColors by remember { mutableStateOf(false) }

    SettingsSectionTitle("输出方式")

    SettingsChoiceRow(
        title = "外部歌词输出",
        choices = ExternalLyricsModeChoices,
        selectedValue = uiSettings.externalLyricsMode.ordinal,
        onSelect = { ordinal ->
            val mode = ExternalLyricsMode.entries[ordinal]
            if (mode != ExternalLyricsMode.OFF && !overlayControl.canDrawOverlays()) {
                Toast.makeText(context, "请先允许悬浮窗权限", Toast.LENGTH_SHORT).show()
                overlayControl.openPermissionSettings()
            } else {
                uiSettings.updateExternalLyricsMode(mode)
                overlayControl.sync()
            }
        },
    )

    if (uiSettings.externalLyricsMode == ExternalLyricsMode.DESKTOP) {
        SettingsSliderRow(
            title = "桌面歌词：原文大小",
            value = uiSettings.desktopLyricsOriginalFontSizeSp,
            valueRange = MIN_LYRICS_PAGE_FONT_SIZE_SP..MAX_LYRICS_PAGE_FONT_SIZE_SP,
            suffix = " sp",
            onValueChange = { uiSettings.updateDesktopLyricsOriginalFontSizeSp(it) },
        )

        SettingsSliderRow(
            title = "桌面歌词：译文大小",
            value = uiSettings.desktopLyricsTranslationFontSizeSp,
            valueRange = MIN_LYRICS_PAGE_FONT_SIZE_SP..MAX_LYRICS_PAGE_FONT_SIZE_SP,
            suffix = " sp",
            onValueChange = { uiSettings.updateDesktopLyricsTranslationFontSizeSp(it) },
        )

        SettingsChoiceRow(
            title = "桌面歌词显示内容",
            subtitle = "无译文时回退原文",
            choices = ExternalLyricsBilingualDisplayChoices,
            selectedValue = uiSettings.desktopLyricsBilingualDisplayMode.ordinal,
            onSelect = { ordinal ->
                uiSettings.updateDesktopLyricsBilingualDisplayMode(
                    LyricsBilingualDisplayMode.entries[ordinal],
                )
            },
        )

        SettingsToggleRow(
            title = "桌面歌词逐字",
            subtitle = "无逐字时间轴时回退逐行",
            checked = uiSettings.desktopLyricsWordByWordEnabled,
            onCheckedChange = { uiSettings.updateDesktopLyricsWordByWordEnabled(it) },
        )

        SettingsSliderRow(
            title = "桌面歌词可用宽度",
            subtitle = "100% 仍保留两侧边距",
            value = uiSettings.desktopLyricsWidthPercent,
            valueRange = MIN_EXTERNAL_LYRICS_WIDTH_PERCENT..MAX_EXTERNAL_LYRICS_WIDTH_PERCENT,
            suffix = "%",
            onValueChange = {
                uiSettings.updateDesktopLyricsWidthPercent(it)
                overlayControl.refreshSettings()
            },
        )
    }

    if (uiSettings.externalLyricsMode == ExternalLyricsMode.STATUS_BAR) {
        SettingsSliderRow(
            title = "状态栏歌词：原文大小",
            value = uiSettings.statusBarLyricsOriginalFontSizeSp,
            valueRange = MIN_LYRICS_PAGE_FONT_SIZE_SP..MAX_LYRICS_PAGE_FONT_SIZE_SP,
            suffix = " sp",
            onValueChange = { uiSettings.updateStatusBarLyricsOriginalFontSizeSp(it) },
        )

        SettingsSliderRow(
            title = "状态栏歌词：译文大小",
            value = uiSettings.statusBarLyricsTranslationFontSizeSp,
            valueRange = MIN_LYRICS_PAGE_FONT_SIZE_SP..MAX_LYRICS_PAGE_FONT_SIZE_SP,
            suffix = " sp",
            onValueChange = { uiSettings.updateStatusBarLyricsTranslationFontSizeSp(it) },
        )

        SettingsToggleRow(
            title = "状态栏歌词分割双语",
            subtitle = "关闭后合并为一行",
            checked = uiSettings.statusBarLyricsSplitEnabled,
            onCheckedChange = { uiSettings.updateStatusBarLyricsSplitEnabled(it) },
        )

        SettingsChoiceRow(
            title = "状态栏歌词显示内容",
            subtitle = "无译文时回退原文",
            choices = ExternalLyricsBilingualDisplayChoices,
            selectedValue = uiSettings.statusBarLyricsBilingualDisplayMode.ordinal,
            onSelect = { ordinal ->
                uiSettings.updateStatusBarLyricsBilingualDisplayMode(
                    LyricsBilingualDisplayMode.entries[ordinal],
                )
            },
        )

        SettingsToggleRow(
            title = "状态栏歌词逐字",
            subtitle = "无逐字时间轴时回退逐行",
            checked = uiSettings.statusBarLyricsWordByWordEnabled,
            onCheckedChange = { uiSettings.updateStatusBarLyricsWordByWordEnabled(it) },
        )

        SettingsSliderRow(
            title = "状态栏歌词上下位置",
            subtitle = "相对屏幕顶部",
            value = uiSettings.statusBarLyricsTopOffsetDp,
            valueRange = MIN_STATUS_BAR_LYRICS_TOP_OFFSET_DP..MAX_STATUS_BAR_LYRICS_TOP_OFFSET_DP,
            suffix = " dp",
            onValueChange = {
                uiSettings.updateStatusBarLyricsTopOffsetDp(it)
                overlayControl.refreshPosition()
            },
        )

        SettingsSliderRow(
            title = "状态栏歌词左右微调",
            subtitle = "负值向左，正值向右",
            value = uiSettings.statusBarLyricsHorizontalOffsetDp,
            valueRange = MIN_STATUS_BAR_LYRICS_HORIZONTAL_OFFSET_DP..MAX_STATUS_BAR_LYRICS_HORIZONTAL_OFFSET_DP,
            suffix = " dp",
            onValueChange = {
                uiSettings.updateStatusBarLyricsHorizontalOffsetDp(it)
                overlayControl.refreshPosition()
            },
        )

        SettingsSliderRow(
            title = "状态栏歌词可用宽度",
            subtitle = "100% 仍保留两侧边距",
            value = uiSettings.statusBarLyricsWidthPercent,
            valueRange = MIN_EXTERNAL_LYRICS_WIDTH_PERCENT..MAX_EXTERNAL_LYRICS_WIDTH_PERCENT,
            suffix = "%",
            onValueChange = { uiSettings.updateStatusBarLyricsWidthPercent(it) },
        )

        SettingsChoiceRow(
            title = "状态栏歌词文字对齐",
            choices = StatusBarLyricsTextAlignmentChoices,
            selectedValue = uiSettings.statusBarLyricsTextAlignment.ordinal,
            onSelect = { ordinal ->
                uiSettings.updateStatusBarLyricsTextAlignment(
                    LyricsPageAlignment.entries[ordinal],
                )
            },
        )
    }

    if (uiSettings.externalLyricsMode != ExternalLyricsMode.OFF) {
        SettingsSectionTitle("通用样式")

        SettingsChoiceRow(
            title = "外部歌词显示规则",
            subtitle = "可在 Mica 前台时自动隐藏",
            choices = ExternalLyricsVisibilityChoices,
            selectedValue = uiSettings.externalLyricsVisibilityMode.ordinal,
            onSelect = { ordinal ->
                uiSettings.updateExternalLyricsVisibilityMode(
                    ExternalLyricsVisibilityMode.entries[ordinal],
                )
            },
        )

        SettingsChoiceRow(
            title = "外部歌词颜色",
            subtitle = "支持单色或最多四色渐变",
            choices = ExternalLyricsColorModeChoices,
            selectedValue = uiSettings.externalLyricsColorMode.ordinal,
            onSelect = { ordinal ->
                uiSettings.updateExternalLyricsColorMode(ExternalLyricsColorMode.entries[ordinal])
            },
        )

        if (uiSettings.externalLyricsColorMode == ExternalLyricsColorMode.GRADIENT) {
            SettingsDropdownRow(
                title = "渐变颜色数量",
                choices = ExternalLyricsColorCountChoices,
                selectedValue = uiSettings.externalLyricsColorCount,
                onSelect = { uiSettings.updateExternalLyricsColorCount(it) },
            )

            SettingsDropdownRow(
                title = "渐变角度",
                choices = ExternalLyricsGradientAngleChoices,
                selectedValue = uiSettings.externalLyricsGradientAngleDegrees,
                onSelect = { uiSettings.updateExternalLyricsGradientAngleDegrees(it) },
            )
        }

        SettingsActionRow(
            title = "编辑外部歌词颜色",
            subtitle = "当前 ${uiSettings.externalLyricsColors.take(uiSettings.externalLyricsColorCount).joinToString { formatExternalLyricsHex(it) }}",
            onClick = { showExternalLyricsColors = true },
        )

        if (showExternalLyricsColors) {
            ExternalLyricsColorDialog(
                initialColors = uiSettings.externalLyricsColors,
                colorCount = if (uiSettings.externalLyricsColorMode == ExternalLyricsColorMode.SINGLE) {
                    1
                } else {
                    uiSettings.externalLyricsColorCount
                },
                onDismiss = { showExternalLyricsColors = false },
                onConfirm = { colors ->
                    uiSettings.updateExternalLyricsColors(colors)
                    showExternalLyricsColors = false
                },
            )
        }

        SettingsSliderRow(
            title = "已填充歌词透明度",
            subtitle = "只影响逐字填充后的文字",
            value = uiSettings.externalLyricsOpacityPercent,
            valueRange = MIN_EXTERNAL_LYRICS_EFFECT_PERCENT..MAX_EXTERNAL_LYRICS_EFFECT_PERCENT,
            suffix = "%",
            onValueChange = { uiSettings.updateExternalLyricsOpacityPercent(it) },
        )

        SettingsSliderRow(
            title = "外部歌词阴影强度",
            value = uiSettings.externalLyricsShadowStrengthPercent,
            valueRange = MIN_EXTERNAL_LYRICS_EFFECT_PERCENT..MAX_EXTERNAL_LYRICS_EFFECT_PERCENT,
            suffix = "%",
            onValueChange = { uiSettings.updateExternalLyricsShadowStrengthPercent(it) },
        )

        SettingsSliderRow(
            title = "外部歌词发光强度",
            value = uiSettings.externalLyricsGlowStrengthPercent,
            valueRange = MIN_EXTERNAL_LYRICS_EFFECT_PERCENT..MAX_EXTERNAL_LYRICS_EFFECT_PERCENT,
            suffix = "%",
            onValueChange = { uiSettings.updateExternalLyricsGlowStrengthPercent(it) },
        )
    }
}

private fun formatExternalLyricsHex(colorArgb: Int): String =
    "#" + Integer.toUnsignedString(colorArgb, 16).padStart(8, '0').uppercase()
