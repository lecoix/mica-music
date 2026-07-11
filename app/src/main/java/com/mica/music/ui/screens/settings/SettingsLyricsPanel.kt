package com.mica.music.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mica.music.data.AppFontImporter
import com.mica.music.data.AppFontSelection
import com.mica.music.data.AppFontSource
import com.mica.music.data.AppUiSettings
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsPageAlignment
import com.mica.music.data.LyricsPageTheme
import com.mica.music.data.PlaybackContentColorMode
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsDropdownRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsToggleRow
import com.mica.music.ui.theme.HifiSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LyricsSettingsPanel(
    uiSettings: AppUiSettings,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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

    SettingsSectionTitle("歌词页")

    SettingsChoiceRow(
        title = "歌词页主题",
        subtitle = "歌词云会隐藏标题、进度条和播放按钮，让整页用于显示歌词",
        choices = LyricsPageThemeChoices,
        selectedValue = uiSettings.lyricsPageTheme.ordinal,
        onSelect = { ordinal ->
            uiSettings.updateLyricsPageTheme(LyricsPageTheme.entries[ordinal])
        },
    )

    SettingsToggleRow(
        title = "分割双语歌词",
        subtitle = "将含细空格（U+2009 等）或 //、／ 的行拆成上下两行；关闭后每行 LRC 保持一行",
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

    Spacer(Modifier.height(HifiSpacing.lg))

    SettingsSectionTitle("字体")

    SettingsActionRow(
        title = "歌词字体",
        subtitle = "当前：${uiSettings.lyricFont.settingsLabel}",
        onClick = { fontPicker.launch(arrayOf("*/*")) },
    )

    SettingsActionRow(
        title = "导入字体文件",
        subtitle = "支持 TTF / OTF；新导入会覆盖旧的歌词字体",
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
