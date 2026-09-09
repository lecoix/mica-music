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
import com.mica.music.ui.components.SettingsPairedDropdownRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsSliderRow
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
            subtitle = "同时作用于浅色和深色主题",
            value = uiSettings.customWallpaperOverlayPercent,
            valueRange = 0..100,
            suffix = "%",
            onValueChange = uiSettings::updateCustomWallpaperOverlayPercent,
        )

        SettingsSliderRow(
            title = "壁纸模糊度",
            subtitle = "0dp 为关闭",
            value = uiSettings.customWallpaperBlurDp,
            valueRange = 0..32,
            suffix = "dp",
            onValueChange = uiSettings::updateCustomWallpaperBlurDp,
        )

        SettingsActionRow(
            title = "调整壁纸裁切",
            subtitle = "拖动移动 · 双指缩放",
            onClick = onShowCustomWallpaperCrop,
        )
    }

    SettingsActionRow(
        title = "恢复默认壁纸",
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
        subtitle = "直接显示歌单，或进入歌单总览",
        choices = PlaylistSidebarStyleChoices,
        selectedValue = uiSettings.playlistSidebarStyle.ordinal,
        onSelect = { ordinal ->
            uiSettings.updatePlaylistSidebarStyle(PlaylistSidebarStyle.entries[ordinal])
        },
    )

    SettingsChoiceRow(
        title = "隐藏状态栏",
        subtitle = "隐藏后可从顶部下滑临时唤出",
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

    val miniLyricsMode = when {
        !uiSettings.miniPlayerLyricsEnabled -> 0
        uiSettings.miniPlayerWordLyricsEnabled -> 2
        else -> 1
    }
    SettingsChoiceRow(
        title = "迷你播放栏歌词",
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
