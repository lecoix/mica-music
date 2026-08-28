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
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.data.MiniPlayerSwipeAction
import com.mica.music.data.PlaylistSidebarStyle
import com.mica.music.data.StatusBarVisibilityMode
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsDropdownRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsSliderRow
import com.mica.music.ui.components.SettingsToggleRow
import com.mica.music.ui.screens.settings.color.formatAccentHex
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaPreset
import kotlinx.coroutines.launch

@Composable
internal fun AppearanceSettingsPanel(
    uiSettings: AppUiSettings,
    onShowCustomAccentDialog: () -> Unit,
    onShowCustomMicaDialog: () -> Unit,
    onShowCustomWallpaperCrop: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wallpaperPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = uiSettings.prepareCustomWallpaper(uri)
            if (result.applied) {
                onShowCustomWallpaperCrop()
            }
            if (result.message.isNotEmpty()) {
                Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
            }
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
            "动态取色：需要Android 12+，跟随系统主题色"
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
            "选择图片后进入裁切；点应用后替换主界面背景"
        } else {
            "已启用；重新选择会先裁切，播放页与歌词页不受影响"
        },
        onClick = {
            wallpaperPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
    )

    if (uiSettings.customWallpaperPath != null) {
        SettingsSliderRow(
            title = "壁纸遮罩强度",
            subtitle = "同时作用于浅色和深色主题",
            value = uiSettings.customWallpaperOverlayPercent,
            valueRange = 0..100,
            suffix = "%",
            onValueChange = uiSettings::updateCustomWallpaperOverlayPercent,
        )

        SettingsSliderRow(
            title = "壁纸模糊度",
            subtitle = "0dp 为不模糊；当前范围为 0–32dp",
            value = uiSettings.customWallpaperBlurDp,
            valueRange = 0..32,
            suffix = "dp",
            onValueChange = uiSettings::updateCustomWallpaperBlurDp,
        )

        SettingsActionRow(
            title = "调整壁纸裁切",
            subtitle = "拖动移动，双指缩放；参数保存后持续生效",
            onClick = onShowCustomWallpaperCrop,
        )
    }

    SettingsActionRow(
        title = "恢复默认壁纸",
        subtitle = "回到当前云母背景",
        enabled = uiSettings.customWallpaperPath != null,
        onClick = {
            scope.launch {
                uiSettings.clearCustomWallpaper()
                Toast.makeText(context, "已恢复默认壁纸", Toast.LENGTH_SHORT).show()
            }
        },
    )

    SettingsChoiceRow(
        title = "侧栏歌单样式",
        subtitle = "侧栏逐个显示歌单，或进入歌单总览页管理歌单",
        choices = PlaylistSidebarStyleChoices,
        selectedValue = uiSettings.playlistSidebarStyle.ordinal,
        onSelect = { ordinal ->
            uiSettings.updatePlaylistSidebarStyle(PlaylistSidebarStyle.entries[ordinal])
        },
    )

    SettingsChoiceRow(
        title = "隐藏状态栏",
        subtitle = "按页面选择隐藏范围；隐藏后可从屏幕顶部下滑临时唤出",
        choices = StatusBarVisibilityModeChoices,
        selectedValue = uiSettings.statusBarVisibilityMode.ordinal,
        onSelect = { ordinal ->
            uiSettings.updateStatusBarVisibilityMode(StatusBarVisibilityMode.entries[ordinal])
        },
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
        subtitle = "播放中在迷你播放栏显示当前歌词",
        checked = uiSettings.miniPlayerLyricsEnabled,
        onCheckedChange = { uiSettings.updateMiniPlayerLyricsEnabled(it) },
    )

    if (uiSettings.miniPlayerLyricsEnabled) {
        SettingsToggleRow(
            title = "迷你播放栏逐字歌词",
            subtitle = "开启后以柔边逐字填充显示，且仅显示原文；无逐字时间轴时回退为整行歌词",
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
