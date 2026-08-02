package com.mica.music.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mica.music.data.AppAccentColor
import com.mica.music.data.AppThemeMode
import com.mica.music.data.AppUiSettings
import com.mica.music.data.AppWallpaperImporter
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.data.MiniPlayerSwipeAction
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsDropdownRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsToggleRow
import com.mica.music.ui.screens.settings.color.formatAccentHex
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AppearanceSettingsPanel(
    uiSettings: AppUiSettings,
    onShowCustomAccentDialog: () -> Unit,
    onShowCustomMicaDialog: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wallpaperPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AppWallpaperImporter.importWallpaper(context, uri)
            }
            result.path?.let(uiSettings::updateCustomWallpaperPath)
            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
        }
    }

    SettingsSectionTitle("外观与主题")

    SettingsChoiceRow(
        title = "主题",
        choices = ThemeChoices,
        selectedValue = uiSettings.themeMode.ordinal,
        onSelect = { ordinal ->
            val mode = AppThemeMode.entries[ordinal]
            uiSettings.updateThemeMode(mode)
        },
    )

    SettingsChoiceRow(
        title = "强调色",
        subtitle = if (uiSettings.accentColor == AppAccentColor.CUSTOM) {
            "自定义：${formatAccentHex(uiSettings.customAccentColorArgb)}"
        } else {
            "动态取色：Android 12+ 跟随系统主题色"
        },
        choices = AccentColorChoices,
        selectedValue = uiSettings.accentColor.ordinal,
        onSelect = { ordinal ->
            val accent = AppAccentColor.entries[ordinal]
            if (accent == AppAccentColor.CUSTOM) {
                onShowCustomAccentDialog()
            } else {
                uiSettings.updateAccentColor(accent)
            }
        },
    )

    SettingsChoiceRow(
        title = "云母背景",
        subtitle = when {
            uiSettings.micaBackgroundPreset == MicaPreset.CUSTOM && uiSettings.customMicaSingleColor -> {
                "自定义：${formatAccentHex(uiSettings.customMicaStartArgb)}"
            }
            uiSettings.micaBackgroundPreset == MicaPreset.CUSTOM -> {
                "自定义：${formatAccentHex(uiSettings.customMicaStartArgb)} → " +
                    formatAccentHex(uiSettings.customMicaEndArgb)
            }
            else -> "主页与各页面的渐变底色"
        },
        choices = MicaBackgroundChoices,
        selectedValue = uiSettings.micaBackgroundPreset.ordinal,
        onSelect = { ordinal ->
            val preset = MicaPreset.entries[ordinal]
            if (preset == MicaPreset.CUSTOM) {
                onShowCustomMicaDialog()
            } else {
                uiSettings.updateMicaBackgroundPreset(preset)
            }
        },
    )

    SettingsActionRow(
        title = "自定义壁纸",
        subtitle = if (uiSettings.customWallpaperPath == null) {
            "选择主界面背景；播放页与歌词页不受影响"
        } else {
            "已启用；覆盖首页、设置等主界面背景"
        },
        onClick = {
            wallpaperPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
    )

    SettingsActionRow(
        title = "恢复默认壁纸",
        subtitle = "回到当前云母背景",
        enabled = uiSettings.customWallpaperPath != null,
        onClick = {
            AppWallpaperImporter.clearWallpaper(context)
            uiSettings.updateCustomWallpaperPath(null)
            Toast.makeText(context, "已恢复默认壁纸", Toast.LENGTH_SHORT).show()
        },
    )

    SettingsToggleRow(
        title = "隐藏状态栏",
        subtitle = "全屏显示内容；从屏幕顶部下滑可临时唤出状态栏",
        checked = uiSettings.hideStatusBar,
        onCheckedChange = { uiSettings.updateHideStatusBar(it) },
    )

    Spacer(Modifier.height(HifiSpacing.lg))

    SettingsSectionTitle("迷你播放")

    SettingsChoiceRow(
        title = "迷你播放栏",
        choices = MiniPlayerStyleChoices,
        selectedValue = uiSettings.miniPlayerStyle.ordinal,
        onSelect = { ordinal ->
            uiSettings.updateMiniPlayerStyle(MiniPlayerStyle.entries[ordinal])
        },
    )

    SettingsToggleRow(
        title = "迷你播放栏歌词",
        subtitle = "播放中在迷你播放栏显示当前歌词，关闭后显示歌名和歌手",
        checked = uiSettings.miniPlayerLyricsEnabled,
        onCheckedChange = { uiSettings.updateMiniPlayerLyricsEnabled(it) },
    )

    if (uiSettings.miniPlayerLyricsEnabled) {
        SettingsToggleRow(
            title = "迷你播放栏逐字歌词",
            subtitle = "开启后以柔边逐字填充显示，且仅显示原文；无逐字时间轴时回退为整行",
            checked = uiSettings.miniPlayerWordLyricsEnabled,
            onCheckedChange = { uiSettings.updateMiniPlayerWordLyricsEnabled(it) },
        )
    }

    SettingsToggleRow(
        title = "迷你播放栏滑动切歌",
        subtitle = "开启后可在迷你播放栏左右滑动切换歌曲",
        checked = uiSettings.miniPlayerSwipeEnabled,
        onCheckedChange = { uiSettings.updateMiniPlayerSwipeEnabled(it) },
    )

    if (uiSettings.miniPlayerSwipeEnabled) {
        SettingsDropdownRow(
            title = "左滑动作",
            subtitle = "手指向左滑动后的切歌动作",
            choices = MiniPlayerSwipeActionChoices,
            selectedValue = uiSettings.miniPlayerLeftSwipeAction.ordinal,
            onSelect = { ordinal ->
                uiSettings.updateMiniPlayerLeftSwipeAction(
                    MiniPlayerSwipeAction.entries[ordinal],
                )
            },
        )

        SettingsDropdownRow(
            title = "右滑动作",
            subtitle = "手指向右滑动后的切歌动作",
            choices = MiniPlayerSwipeActionChoices,
            selectedValue = uiSettings.miniPlayerRightSwipeAction.ordinal,
            onSelect = { ordinal ->
                uiSettings.updateMiniPlayerRightSwipeAction(
                    MiniPlayerSwipeAction.entries[ordinal],
                )
            },
        )
    }
}
