package com.mica.music.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mica.music.data.AppUiSettings
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.PlaybackContentColorMode
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsToggleRow
import com.mica.music.ui.theme.HifiSpacing

@Composable
internal fun PlaybackSettingsPanel(uiSettings: AppUiSettings) {
    SettingsSectionTitle("封面与播放页")

    SettingsChoiceRow(
        title = "封面显示",
        subtitle = "原样比例：列表/歌词页为正方框内完整显示；播放页大图可按比例；裁切填充：居中裁切",
        choices = CoverDisplayChoices,
        selectedValue = uiSettings.coverDisplayMode.ordinal,
        onSelect = { ordinal ->
            uiSettings.updateCoverDisplayMode(CoverDisplayMode.entries[ordinal])
        },
    )

    SettingsChoiceRow(
        title = "播放页背景",
        choices = PlayerLowerBgChoices,
        selectedValue = uiSettings.playerLowerBackground.ordinal,
        onSelect = { ordinal ->
            uiSettings.updatePlayerLowerBackground(PlayerLowerBackgroundMode.entries[ordinal])
        },
    )

    SettingsChoiceRow(
        title = "播放页 UI 颜色",
        subtitle = "信息行、歌名、艺人、专辑、进度条与底部五个按钮；自动：随播放页背景与封面取色",
        choices = PlaybackContentColorChoices,
        selectedValue = uiSettings.playerPageTextColorMode.ordinal,
        onSelect = { ordinal ->
            uiSettings.updatePlayerPageTextColorMode(
                PlaybackContentColorMode.entries[ordinal],
            )
        },
    )

    SettingsChoiceRow(
        title = "播放页特殊主题",
        subtitle = "所有特殊主题仅使用裁切填充封面",
        choices = PlayerCoverFlowChoices,
        selectedValue = uiSettings.playerCoverFlowMode.ordinal,
        onSelect = { ordinal ->
            uiSettings.updatePlayerCoverFlowMode(PlayerCoverFlowMode.entries[ordinal])
        },
    )

    SettingsToggleRow(
        title = "封面底边进度",
        subtitle = when (uiSettings.playerLowerBackground) {
            PlayerLowerBackgroundMode.THEME,
            PlayerLowerBackgroundMode.COVER_GLOW,
            PlayerLowerBackgroundMode.DYNAMIC_LIGHT,
            PlayerLowerBackgroundMode.DYNAMIC_ARTWORK,
            -> "开启后将进度条与频谱移到专辑图底边；关闭后使用普通布局"
            else -> "标准主题仅「主题色」「封面模糊」下生效；特殊主题仍可在普通与底边布局间切换"
        },
        checked = uiSettings.coverEdgeProgress,
        onCheckedChange = { uiSettings.updateCoverEdgeProgress(it) },
    )

    SettingsToggleRow(
        title = "播放时屏幕常亮",
        subtitle = "仅在播放页打开且正在播放时防止熄屏；暂停或离开播放页后恢复系统设置",
        checked = uiSettings.keepScreenOnWhenPlaying,
        onCheckedChange = { uiSettings.updateKeepScreenOnWhenPlaying(it) },
    )

    SettingsToggleRow(
        title = "下半屏沉浸",
        subtitle = "封面以下仅显示歌名与歌手并居中；点击播放/暂停，长按歌名区域可开关，粒子封面&拍立得回忆不适用（制作中）",
        checked = uiSettings.playerImmersiveLower &&
            uiSettings.playerCoverFlowMode.supportsImmersiveLower,
        onCheckedChange = {
            if (uiSettings.playerCoverFlowMode.supportsImmersiveLower) {
                uiSettings.updatePlayerImmersiveLower(it)
            }
        },
    )

    SettingsToggleRow(
        title = "隐藏歌名括号内容",
        subtitle = "播放页和歌词页仅显示括号前后的歌名；不修改曲库原始标题",
        checked = uiSettings.stripSongTitleParentheses,
        onCheckedChange = { uiSettings.updateStripSongTitleParentheses(it) },
    )

    Spacer(Modifier.height(HifiSpacing.lg))

    SettingsSectionTitle("动态效果")

    SettingsToggleRow(
        title = "频谱条",
        subtitle = "显示随音乐跳动的频段条；位置跟随当前进度条布局",
        checked = uiSettings.spectrumEnabled,
        onCheckedChange = { uiSettings.updateSpectrumEnabled(it) },
    )
}
