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
        subtitle = "歌词云与信笺会隐藏播放控件",
        choices = LyricsPageThemeChoices,
        selectedValue = uiSettings.lyricsPageTheme.ordinal,
        onSelect = { ordinal ->
            uiSettings.updateLyricsPageTheme(LyricsPageTheme.entries[ordinal])
        },
    )

    SettingsDropdownRow(
        title = "歌词优先级",
        subtitle = "缺少首选时自动使用下一项",
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
            subtitle = "保留大小、浓度和旋转设置",
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

    val bilingualLyricsMode = if (uiSettings.lyricSplitEnabled) {
        uiSettings.lyricsBilingualDisplayMode.ordinal + 1
    } else {
        0
    }
    SettingsChoiceRow(
        title = "双语歌词",
        subtitle = if (bilingualLyricsMode == 0) null else "将同行双语歌词拆成上下两行",
        choices = BilingualLyricsModeChoices,
        selectedValue = bilingualLyricsMode,
        onSelect = { mode ->
            uiSettings.updateLyricSplitEnabled(mode != 0)
            if (mode != 0) {
                uiSettings.updateLyricsBilingualDisplayMode(
                    LyricsBilingualDisplayMode.entries[mode - 1],
                )
            }
        },
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
        subtitle = "歌词自带读音时显示在原文上方",
        checked = uiSettings.lyricReadingEnabled,
        onCheckedChange = { uiSettings.updateLyricReadingEnabled(it) },
    )


    SettingsChoiceRow(
        title = "歌词颜色",
        subtitle = "动态取色跟随歌曲；自动模式按背景选择黑白",
        choices = LyricsPageTextColorChoices,
        selectedValue = uiSettings.lyricsPageTextColorMode.ordinal,
        onSelect = { ordinal ->
            uiSettings.updateLyricsPageTextColorMode(
                PlaybackContentColorMode.entries[ordinal],
            )
        },
    )

    SettingsSectionTitle("歌词输出")

    val infoRowLyricsMode = when {
        !uiSettings.infoRowLyricsEnabled -> 0
        uiSettings.infoRowWordLyricsEnabled -> 2
        else -> 1
    }
    SettingsChoiceRow(
        title = "信息行歌词",
        subtitle = when (infoRowLyricsMode) {
            0 -> null
            2 -> "仅原文；无逐字时间轴时显示整行"
            else -> "暂停或无歌词时恢复列表信息"
        },
        choices = InfoRowLyricsModeChoices,
        selectedValue = infoRowLyricsMode,
        onSelect = { mode ->
            uiSettings.updateInfoRowLyricsEnabled(mode != 0)
            uiSettings.updateInfoRowWordLyricsEnabled(mode == 2)
        },
    )
    SettingsToggleRow(
        title = "通知栏歌词",
        subtitle = "主位显示歌词，副位显示歌名与歌手；车载蓝牙兼容为实验功能",
        checked = uiSettings.notificationLyricsEnabled,
        onCheckedChange = { uiSettings.updateNotificationLyricsEnabled(it) },
    )

    SettingsToggleRow(
        title = "词幕歌词",
        subtitle = "提供逐字、翻译与罗马音",
        checked = uiSettings.lyriconLyricsEnabled,
        onCheckedChange = { uiSettings.updateLyriconLyricsEnabled(it) },
    )

    SettingsActionRow(
        title = "外部歌词",
        subtitle = "当前：${uiSettings.externalLyricsMode.settingsLabel}",
        onClick = onOpenExternalLyrics,
    )

    if (uiSettings.lyricsPageTheme == LyricsPageTheme.LIST) {
        Spacer(Modifier.height(HifiSpacing.lg))

        SettingsSectionTitle("经典列表")

        SettingsChoiceRow(
            title = "逐字动画",
            subtitle = "仅作用于真实逐字歌词",
            choices = LyricsWordAnimationPresetChoices,
            selectedValue = uiSettings.lyricsWordAnimationPreset.ordinal,
            onSelect = { ordinal ->
                uiSettings.updateLyricsWordAnimationPreset(LyricsWordAnimationPreset.entries[ordinal])
            },
        )

        SettingsToggleRow(
            title = "强制使用逐字歌词样式",
            subtitle = "无逐字时间轴时按播放进度填充当前句",
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
            subtitle = "隐藏进度条与底部控件",
            checked = uiSettings.lyricsPageImmersive,
            onCheckedChange = { uiSettings.updateLyricsPageImmersive(it) },
        )
    }

    Spacer(Modifier.height(HifiSpacing.lg))

    SettingsSectionTitle("字体")

    SettingsChoiceRow(
        title = "歌词字体",
        subtitle = if (uiSettings.lyricFont.source == AppFontSource.IMPORTED) {
            "当前：${uiSettings.lyricFont.settingsLabel} · 支持 TTF / OTF"
        } else {
            "支持 TTF / OTF"
        },
        choices = if (uiSettings.lyricFont.source == AppFontSource.IMPORTED) {
            listOf(
                AppFontSource.SYSTEM.ordinal to AppFontSource.SYSTEM.settingsLabel,
                AppFontSource.IMPORTED.ordinal to "重新导入…",
            )
        } else {
            FontSourceChoices
        },
        selectedValue = uiSettings.lyricFont.source.ordinal,
        onSelect = { source ->
            when (AppFontSource.entries[source]) {
                AppFontSource.SYSTEM -> uiSettings.updateLyricFont(AppFontSelection.SystemDefault)
                AppFontSource.IMPORTED -> fontPicker.launch(arrayOf("*/*"))
            }
        },
    )
}
