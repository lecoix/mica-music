package com.mica.music.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mica.music.data.AppFontImporter
import com.mica.music.data.AppFontSelection
import com.mica.music.data.AppFontSource
import com.mica.music.data.AppLetterSealImporter
import com.mica.music.data.AppUiSettings
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsPageAlignment
import com.mica.music.data.LyricsPageTheme
import com.mica.music.data.LyricsWordAnimationPreset
import com.mica.music.data.MAX_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.MAX_LYRICS_PAGE_LINE_SPACING_DP
import com.mica.music.data.MIN_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.MIN_LYRICS_PAGE_LINE_SPACING_DP
import com.mica.music.data.PlaybackContentColorMode
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsDropdownRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsSliderRow
import com.mica.music.ui.components.LyricsOffsetSheet
import com.mica.music.ui.components.formatLyricsOffset
import com.mica.music.ui.components.SettingsToggleRow
import com.mica.music.ui.theme.HifiSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LyricsSettingsPanel(
    uiSettings: AppUiSettings,
    onOpenExternalLyrics: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showGlobalLyricsOffset by remember { mutableStateOf(false) }
    val fontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AppFontImporter.importLyricFont(context, uri)
            }
            result.selection?.let(uiSettings::updateLyricFont)
            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
        }
    }
    val sealImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AppLetterSealImporter.importSeal(context, uri)
            }
            result.path?.let(uiSettings::updateLetterSealCustomImagePath)
            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
        }
    }

    SettingsSectionTitle("主题")

    SettingsChoiceRow(
        title = "歌词页主题",
        subtitle = "歌词云与信笺会隐藏标题、进度条和播放按钮，让整页用于显示歌词",
        choices = LyricsPageThemeChoices,
        selectedValue = uiSettings.lyricsPageTheme.ordinal,
        onSelect = { ordinal ->
            uiSettings.updateLyricsPageTheme(LyricsPageTheme.entries[ordinal])
        },
    )

    SettingsDropdownRow(
        title = "歌词优先级",
        subtitle = "按顺序选择已扫描的歌词；缺少前一项时自动使用下一项",
        choices = LyricsPriorityChoices.mapIndexed { index, (_, label) -> index to label },
        selectedValue = LyricsPriorityChoices.indexOfFirst {
            it.first == uiSettings.lyricsSlotPriority
        }.coerceAtLeast(0),
        onSelect = { index ->
            LyricsPriorityChoices.getOrNull(index)?.first?.let(uiSettings::updateLyricsSlotPriority)
        },
    )

    if (uiSettings.lyricsPageTheme == LyricsPageTheme.LETTER) {
        Spacer(Modifier.height(HifiSpacing.lg))
        SettingsSectionTitle("信笺朱印")

        SettingsActionRow(
            title = "朱印图片",
            subtitle = if (uiSettings.letterSealCustomImagePath == null) {
                "当前：默认印章；建议使用透明 PNG / WebP"
            } else {
                "当前：自定义图片；新导入会覆盖旧图片"
            },
            onClick = {
                sealImagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )

        SettingsActionRow(
            title = "恢复默认印章",
            subtitle = "恢复应用内置朱印，不改变大小、浓度和旋转",
            enabled = uiSettings.letterSealCustomImagePath != null,
            onClick = {
                AppLetterSealImporter.clearSeal(context)
                uiSettings.updateLetterSealCustomImagePath(null)
                Toast.makeText(context, "已恢复默认信笺朱印", Toast.LENGTH_SHORT).show()
            },
        )

        SettingsDropdownRow(
            title = "朱印大小",
            choices = LetterSealSizeChoices,
            selectedValue = uiSettings.letterSealSizeDp,
            onSelect = uiSettings::updateLetterSealSizeDp,
        )

        SettingsDropdownRow(
            title = "朱印浓度",
            choices = LetterSealOpacityChoices,
            selectedValue = uiSettings.letterSealOpacityPercent,
            onSelect = uiSettings::updateLetterSealOpacityPercent,
        )

        SettingsDropdownRow(
            title = "朱印旋转",
            choices = LetterSealRotationChoices,
            selectedValue = uiSettings.letterSealRotationDegrees,
            onSelect = uiSettings::updateLetterSealRotationDegrees,
        )
    }

    Spacer(Modifier.height(HifiSpacing.lg))

    SettingsSectionTitle("通用显示")

    SettingsActionRow(
        title = "全局歌词偏移",
        subtitle = "当前 ${formatLyricsOffset(uiSettings.globalLyricsOffsetMs)}；正数提前，负数延后",
        onClick = { showGlobalLyricsOffset = true },
    )

    SettingsToggleRow(
        title = "分割双语歌词",
        subtitle = "将含细空格（U+2009 等）或 //、／ 的行拆成上下两行；关闭后每行 LRC 保持一行",
        checked = uiSettings.lyricSplitEnabled,
        onCheckedChange = { uiSettings.updateLyricSplitEnabled(it) },
    )

    if (showGlobalLyricsOffset) {
        LyricsOffsetSheet(
            globalOffsetMs = uiSettings.globalLyricsOffsetMs,
            onGlobalOffsetChange = uiSettings::updateGlobalLyricsOffsetMs,
            onDismiss = { showGlobalLyricsOffset = false },
        )
    }

    SettingsToggleRow(
        title = "显示读音 / 罗马音",
        subtitle = "歌词文件自带读音时显示在原文上方（TTML x-roman* / Apple transliterations；LRC/SPL 同戳三行或主行后罗马音行）",
        checked = uiSettings.lyricReadingEnabled,
        onCheckedChange = { uiSettings.updateLyricReadingEnabled(it) },
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

    SettingsChoiceRow(
        title = "歌词颜色",
        subtitle = "动态取色：稳定主色 + 语义色阶（浅色更鲜艳）；自动：随背景判断黑白",
        choices = LyricsPageTextColorChoices,
        selectedValue = uiSettings.lyricsPageTextColorMode.ordinal,
        onSelect = { ordinal ->
            uiSettings.updateLyricsPageTextColorMode(
                PlaybackContentColorMode.entries[ordinal],
            )
        },
    )

    SettingsSectionTitle("歌词输出")

    SettingsToggleRow(
        title = "信息行歌词",
        subtitle = "播放时在列表信息行显示当前歌词；暂停或无歌词时仍显示列表信息",
        checked = uiSettings.infoRowLyricsEnabled,
        onCheckedChange = { uiSettings.updateInfoRowLyricsEnabled(it) },
    )

    if (uiSettings.infoRowLyricsEnabled) {
        SettingsToggleRow(
            title = "信息行逐字歌词",
            subtitle = "开启后以柔边逐字填充显示，且仅显示原文；无逐字时间轴时回退为整行",
            checked = uiSettings.infoRowWordLyricsEnabled,
            onCheckedChange = { uiSettings.updateInfoRowWordLyricsEnabled(it) },
        )
    }

    SettingsToggleRow(
        title = "通知栏歌词",
        subtitle = "在系统媒体通知主位显示当前歌词，兼容车载蓝牙设备复用同一输出；副位显示歌名与歌手",
        checked = uiSettings.notificationLyricsEnabled,
        onCheckedChange = { uiSettings.updateNotificationLyricsEnabled(it) },
    )

    SettingsActionRow(
        title = "外部歌词",
        subtitle = "当前：${uiSettings.externalLyricsMode.settingsLabel}；桌面歌词与状态栏歌词设置",
        onClick = onOpenExternalLyrics,
    )

    if (uiSettings.lyricsPageTheme == LyricsPageTheme.LIST) {
        Spacer(Modifier.height(HifiSpacing.lg))

        SettingsSectionTitle("经典列表")

        SettingsChoiceRow(
            title = "逐字动画",
            subtitle = "用于经典列表及歌词云不可用时的回退页面；仅影响带有真实逐字时间轴的歌词",
            choices = LyricsWordAnimationPresetChoices,
            selectedValue = uiSettings.lyricsWordAnimationPreset.ordinal,
            onSelect = { ordinal ->
                uiSettings.updateLyricsWordAnimationPreset(LyricsWordAnimationPreset.entries[ordinal])
            },
        )

        SettingsToggleRow(
            title = "强制使用逐字歌词样式",
            subtitle = "用于经典列表与播放页迷你歌词；没有逐字时间轴时，当前句按播放进度从左到右填充",
            checked = uiSettings.lyricLineFillEnabled,
            onCheckedChange = { uiSettings.updateLyricLineFillEnabled(it) },
        )

        SettingsChoiceRow(
            title = "歌词页对齐",
            choices = LyricsPageAlignmentChoices,
            selectedValue = uiSettings.lyricsPageAlignment.ordinal,
            onSelect = { ordinal ->
                uiSettings.updateLyricsPageAlignment(LyricsPageAlignment.entries[ordinal])
            },
        )

        SettingsSliderRow(
            title = "原歌词字号",
            value = uiSettings.lyricsPageFontSizeSp,
            valueRange = MIN_LYRICS_PAGE_FONT_SIZE_SP..MAX_LYRICS_PAGE_FONT_SIZE_SP,
            suffix = " sp",
            onValueChange = { uiSettings.updateLyricsPageFontSizeSp(it) },
        )

        SettingsSliderRow(
            title = "翻译歌词字号",
            value = uiSettings.lyricsPageTranslationFontSizeSp,
            valueRange = MIN_LYRICS_PAGE_FONT_SIZE_SP..MAX_LYRICS_PAGE_FONT_SIZE_SP,
            suffix = " sp",
            onValueChange = { uiSettings.updateLyricsPageTranslationFontSizeSp(it) },
        )

        SettingsSliderRow(
            title = "行间距",
            value = uiSettings.lyricsPageLineSpacingDp,
            valueRange = MIN_LYRICS_PAGE_LINE_SPACING_DP..MAX_LYRICS_PAGE_LINE_SPACING_DP,
            suffix = " dp",
            onValueChange = { uiSettings.updateLyricsPageLineSpacingDp(it) },
        )

        SettingsToggleRow(
            title = "歌词页沉浸模式",
            subtitle = "开启后歌词页隐藏进度条和底部五个按钮；在歌词页长按播放按钮也可切换",
            checked = uiSettings.lyricsPageImmersive,
            onCheckedChange = { uiSettings.updateLyricsPageImmersive(it) },
        )
    }

    Spacer(Modifier.height(HifiSpacing.lg))

    SettingsSectionTitle("字体")

    SettingsActionRow(
        title = "歌词字体",
        subtitle = "当前：${uiSettings.lyricFont.settingsLabel}；点击导入 TTF / OTF 字体",
        onClick = { fontPicker.launch(arrayOf("*/*")) },
    )

    SettingsActionRow(
        title = "清除导入字体",
        subtitle = "回到系统默认歌词字体",
        enabled = uiSettings.lyricFont.source == AppFontSource.IMPORTED,
        onClick = {
            AppFontImporter.clearLyricFont(context)
            uiSettings.updateLyricFont(AppFontSelection.SystemDefault)
            Toast.makeText(context, "已恢复系统默认歌词字体", Toast.LENGTH_SHORT).show()
        },
    )
}
