package com.mica.music.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import com.mica.music.data.AppAccentColor
import com.mica.music.data.AppLauncherIcon
import com.mica.music.data.AppThemeMode
import com.mica.music.data.AppUiSettings
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.data.MiniPlayerSwipeAction
import com.mica.music.data.PlaylistSidebarStyle
import com.mica.music.data.StatusBarVisibilityMode
import com.mica.music.ui.components.SettingsNavigationRow
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsPairedDropdownRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsSliderRow
import com.mica.music.ui.screens.settings.color.formatAccentHex
import com.mica.music.ui.system.LauncherIconController
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaPreset
import kotlinx.coroutines.launch

@Composable
internal fun AppearanceSettingsPanel(
    uiSettings: AppUiSettings,
    onShowCustomAccentDialog: () -> Unit,
    onShowCustomMicaDialog: () -> Unit,
    onOpenWallpaper: () -> Unit,
    onOpenMiniPlayer: () -> Unit,
) {
    SettingsSectionTitle("外观与主题")

    SettingsChoiceRow(
        title = "主题",
        modifier = settingsSearchAnchor("appearance.theme"),
        choices = ThemeChoices,
        selectedValue = uiSettings.themeMode.ordinal,
        onSelect = { ordinal ->
            val mode = AppThemeMode.entries[ordinal]
            uiSettings.updateThemeMode(mode)
        },
    )

    SettingsChoiceRow(
        title = "强调色",
        modifier = settingsSearchAnchor("appearance.accent"),
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

    LauncherIconSettingRow()

    SettingsChoiceRow(
        title = "云母背景",
        modifier = settingsSearchAnchor("appearance.mica-background"),
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

    SettingsNavigationRow(
        title = "壁纸",
        subtitle = if (uiSettings.customWallpaperPath == null) "默认云母背景" else "自定义图片 · 裁切、遮罩与模糊",
        onClick = onOpenWallpaper,
    )

    SettingsChoiceRow(
        title = "侧栏歌单样式",
        modifier = settingsSearchAnchor("appearance.playlist-sidebar-style"),
        subtitle = "直接显示歌单，或进入歌单总览",
        choices = PlaylistSidebarStyleChoices,
        selectedValue = uiSettings.playlistSidebarStyle.ordinal,
        onSelect = { ordinal ->
            uiSettings.updatePlaylistSidebarStyle(PlaylistSidebarStyle.entries[ordinal])
        },
    )

    SettingsChoiceRow(
        title = "隐藏状态栏",
        modifier = settingsSearchAnchor("appearance.hide-status-bar"),
        subtitle = "隐藏后可从顶部下滑临时唤出",
        choices = StatusBarVisibilityModeChoices,
        selectedValue = uiSettings.statusBarVisibilityMode.ordinal,
        onSelect = { ordinal ->
            uiSettings.updateStatusBarVisibilityMode(StatusBarVisibilityMode.entries[ordinal])
        },
    )

    SettingsNavigationRow(
        title = "迷你播放栏",
        subtitle = "${uiSettings.miniPlayerStyle.settingsLabel} · 歌词与滑动手势",
        onClick = onOpenMiniPlayer,
    )
}

@Composable
private fun LauncherIconSettingRow() {
    val context = LocalContext.current
    var currentIcon by remember { mutableStateOf(LauncherIconController.current(context)) }
    var pendingIcon by remember { mutableStateOf<AppLauncherIcon?>(null) }

    SettingsChoiceRow(
        title = "应用图标",
        modifier = settingsSearchAnchor("appearance.launcher-icon"),
        subtitle = "桌面图标配色",
        choices = LauncherIconChoices,
        selectedValue = currentIcon.ordinal,
        onSelect = { ordinal ->
            val icon = AppLauncherIcon.entries[ordinal]
            if (icon != currentIcon) pendingIcon = icon
        },
    )

    pendingIcon?.let { icon ->
        AlertDialog(
            onDismissRequest = { pendingIcon = null },
            shape = RectangleShape,
            title = { Text("更换为「${icon.settingsLabel}」图标") },
            text = {
                Text(
                    "桌面图标可能需要几秒才会刷新；部分桌面会移除主屏上的旧图标，" +
                        "需要从应用列表重新添加。长按图标的快捷方式也可能需要重新添加。",
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingIcon = null }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        LauncherIconController.apply(context, icon)
                        currentIcon = LauncherIconController.current(context)
                        pendingIcon = null
                    },
                ) { Text("更换") }
            },
        )
    }
}

@Composable
internal fun WallpaperSettingsPanel(
    uiSettings: AppUiSettings,
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

    SettingsActionRow(
        title = "自定义壁纸",
        modifier = settingsSearchAnchor("appearance.wallpaper"),
        subtitle = if (uiSettings.customWallpaperPath == null) {
            "选择并裁切图片"
        } else {
            "已启用 · 不影响播放页与歌词页"
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
            modifier = settingsSearchAnchor("appearance.wallpaper-overlay"),
            subtitle = "同时作用于浅色和深色主题",
            value = uiSettings.customWallpaperOverlayPercent,
            valueRange = 0..100,
            suffix = "%",
            onValueChange = uiSettings::updateCustomWallpaperOverlayPercent,
        )

        SettingsSliderRow(
            title = "壁纸模糊度",
            modifier = settingsSearchAnchor("appearance.wallpaper-blur"),
            subtitle = "0dp 为关闭",
            value = uiSettings.customWallpaperBlurDp,
            valueRange = 0..32,
            suffix = "dp",
            onValueChange = uiSettings::updateCustomWallpaperBlurDp,
        )

        SettingsActionRow(
            title = "调整壁纸裁切",
            modifier = settingsSearchAnchor("appearance.wallpaper-crop"),
            subtitle = "拖动移动 · 双指缩放",
            onClick = onShowCustomWallpaperCrop,
        )
    }

    SettingsActionRow(
        title = "恢复默认壁纸",
        modifier = settingsSearchAnchor("appearance.restore-wallpaper"),
        enabled = uiSettings.customWallpaperPath != null,
        onClick = {
            scope.launch {
                uiSettings.clearCustomWallpaper()
                Toast.makeText(context, "已恢复默认壁纸", Toast.LENGTH_SHORT).show()
            }
        },
    )

}

@Composable
internal fun MiniPlayerSettingsPanel(uiSettings: AppUiSettings) {
    Spacer(Modifier.height(HifiSpacing.lg))

    SettingsSectionTitle("迷你播放")

    SettingsChoiceRow(
        title = "迷你播放栏",
        modifier = settingsSearchAnchor("appearance.mini-player-style"),
        choices = MiniPlayerStyleChoices,
        selectedValue = uiSettings.miniPlayerStyle.ordinal,
        onSelect = { ordinal ->
            uiSettings.updateMiniPlayerStyle(MiniPlayerStyle.entries[ordinal])
        },
    )

    val miniLyricsMode = when {
        !uiSettings.miniPlayerLyricsEnabled -> 0
        uiSettings.miniPlayerWordLyricsEnabled -> 2
        else -> 1
    }
    SettingsChoiceRow(
        title = "迷你播放栏歌词",
        modifier = settingsSearchAnchor("appearance.mini-player-lyrics"),
        subtitle = if (miniLyricsMode == 2) "仅原文；无逐字时间轴时显示整行" else null,
        choices = MiniPlayerLyricsModeChoices,
        selectedValue = miniLyricsMode,
        onSelect = { mode ->
            uiSettings.updateMiniPlayerLyricsEnabled(mode != 0)
            uiSettings.updateMiniPlayerWordLyricsEnabled(mode == 2)
        },
    )

    val effectiveLeftSwipeAction = if (uiSettings.miniPlayerSwipeEnabled) {
        uiSettings.miniPlayerLeftSwipeAction
    } else {
        MiniPlayerSwipeAction.NONE
    }
    val effectiveRightSwipeAction = if (uiSettings.miniPlayerSwipeEnabled) {
        uiSettings.miniPlayerRightSwipeAction
    } else {
        MiniPlayerSwipeAction.NONE
    }
    fun updateSwipeAction(isLeft: Boolean, action: MiniPlayerSwipeAction) {
        val nextLeft = if (isLeft) action else effectiveLeftSwipeAction
        val nextRight = if (isLeft) effectiveRightSwipeAction else action
        uiSettings.updateMiniPlayerLeftSwipeAction(nextLeft)
        uiSettings.updateMiniPlayerRightSwipeAction(nextRight)
        uiSettings.updateMiniPlayerSwipeEnabled(
            nextLeft != MiniPlayerSwipeAction.NONE || nextRight != MiniPlayerSwipeAction.NONE,
        )
    }
    SettingsPairedDropdownRow(
        title = "滑动切歌",
        modifier = settingsSearchAnchor("appearance.mini-player-swipe"),
        choices = MiniPlayerSwipeActionChoices,
        leftTitle = "左滑",
        leftSelectedValue = effectiveLeftSwipeAction.ordinal,
        onLeftSelect = { ordinal ->
            updateSwipeAction(
                isLeft = true,
                action = MiniPlayerSwipeAction.entries[ordinal],
            )
        },
        rightTitle = "右滑",
        rightSelectedValue = effectiveRightSwipeAction.ordinal,
        onRightSelect = { ordinal ->
            updateSwipeAction(
                isLeft = false,
                action = MiniPlayerSwipeAction.entries[ordinal],
            )
        },
    )
}
