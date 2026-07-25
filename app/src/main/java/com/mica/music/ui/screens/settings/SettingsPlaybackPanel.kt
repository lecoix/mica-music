package com.mica.music.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mica.music.data.AppUiSettings
import com.mica.music.data.CompactLyricsLineMode
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.PlaybackContentColorMode
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.PlayerInfoVisibility
import com.mica.music.data.ReplayGainMode
import com.mica.music.data.preferences.ReplayGainPreferences
import com.mica.music.data.usesCompactLyricsLinePreference
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsToggleRow
import com.mica.music.ui.components.SettingsTextFieldRow
import com.mica.music.ui.theme.HifiSpacing

@Composable
internal fun PlaybackSettingsPanel(uiSettings: AppUiSettings) {
    val context = LocalContext.current
    var replayGainMode by remember { mutableStateOf(ReplayGainPreferences.mode(context)) }

    SettingsSectionTitle("音量标准化")
    SettingsChoiceRow(
        title = "ReplayGain",
        subtitle = "按标签降低音量并防止削波；无有效标签时保持原始音量",
        choices = ReplayGainChoices,
        selectedValue = replayGainMode.ordinal,
        onSelect = { ordinal ->
            replayGainMode = ReplayGainMode.entries[ordinal]
            ReplayGainPreferences.setMode(context, replayGainMode)
        },
    )

    Spacer(Modifier.height(HifiSpacing.lg))

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
        title = "视频专辑封面",
        subtitle = "默认关闭；开启后重扫文件夹曲库，匹配歌曲同目录内与专辑同名的 MP4，仅在标准播放页静音循环播放",
        checked = uiSettings.videoAlbumCoverEnabled,
        onCheckedChange = uiSettings::updateVideoAlbumCoverEnabled,
    )

    if (uiSettings.playerCoverFlowMode == PlayerCoverFlowMode.CUSTOM_STANDARD) {
        SettingsToggleRow(
            title = "点击封面暂停/播放",
            subtitle = "仅在自定义标准主题生效；不影响左右滑动切歌与长按菜单",
            checked = uiSettings.customStandardCoverTapPlayPause,
            onCheckedChange = uiSettings::updateCustomStandardCoverTapPlayPause,
        )
        CustomPlayerLayoutEditor(
            config = uiSettings.customPlayerLowerLayout,
            onChange = uiSettings::updateCustomPlayerLowerLayout,
        )
    }

    if (uiSettings.playerCoverFlowMode != PlayerCoverFlowMode.CUSTOM_STANDARD) {
        SettingsToggleRow(
            title = "封面底边进度",
            subtitle = when {
                uiSettings.playerCoverFlowMode == PlayerCoverFlowMode.PARTICLE_COVER ->
                    "开启后隐藏进度条与频谱；关闭后使用普通布局"
                uiSettings.playerLowerBackground == PlayerLowerBackgroundMode.THEME ||
                    uiSettings.playerLowerBackground.usesBlurredArtwork ->
                    "开启后将进度条与频谱移到专辑图底边；关闭后使用普通布局"
                else ->
                    "标准主题仅「封面渐变」下不生效；特殊主题仍可在普通与底边布局间切换"
            },
            checked = uiSettings.coverEdgeProgress,
            onCheckedChange = { uiSettings.updateCoverEdgeProgress(it) },
        )
    }

    SettingsToggleRow(
        title = "播放时屏幕常亮",
        subtitle = "仅在播放页打开且正在播放时防止熄屏；暂停或离开播放页后恢复系统设置",
        checked = uiSettings.keepScreenOnWhenPlaying,
        onCheckedChange = { uiSettings.updateKeepScreenOnWhenPlaying(it) },
    )

    if (uiSettings.playerCoverFlowMode != PlayerCoverFlowMode.CUSTOM_STANDARD) {
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
    }

    if (uiSettings.playerCoverFlowMode.usesCompactLyricsLinePreference()) {
        SettingsChoiceRow(
            title = "折叠歌词行数",
            subtitle = "自动：按可用高度在一行与三行间切换；三行显示上一句/当前/下一句；一行仅当前句",
            choices = CompactLyricsLineModeChoices,
            selectedValue = uiSettings.compactLyricsLineMode.ordinal,
            onSelect = { ordinal ->
                uiSettings.updateCompactLyricsLineMode(CompactLyricsLineMode.entries[ordinal])
            },
        )
    }

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

    Spacer(Modifier.height(HifiSpacing.lg))
    SettingsSectionTitle("信息行内容")

    val playerInfo = uiSettings.playerInfoVisibility
    fun updatePlayerInfo(transform: (PlayerInfoVisibility) -> PlayerInfoVisibility) {
        uiSettings.updatePlayerInfoVisibility(transform(uiSettings.playerInfoVisibility))
    }
    SettingsToggleRow("格式", "显示容器格式，如 FLAC、MP3", playerInfo.showFormat, { checked ->
        updatePlayerInfo { it.copy(showFormat = checked) }
    })
    SettingsToggleRow("位深/采样率", "显示如 24bit/96kHz", playerInfo.showSampleRate, { checked ->
        updatePlayerInfo { it.copy(showSampleRate = checked) }
    })
    SettingsToggleRow("比特率", "显示如 320 kbps", playerInfo.showBitrate, { checked ->
        updatePlayerInfo { it.copy(showBitrate = checked) }
    })
    SettingsToggleRow("速度", "显示当前播放速度，如 1.25x", playerInfo.showPlaybackSpeed, { checked ->
        updatePlayerInfo { it.copy(showPlaybackSpeed = checked) }
    })
    SettingsToggleRow("音高", "显示当前变调，如 +2 半音", playerInfo.showPlaybackPitch, { checked ->
        updatePlayerInfo { it.copy(showPlaybackPitch = checked) }
    })
    SettingsToggleRow("时间", "显示当前系统时间", playerInfo.showCurrentTime, { checked ->
        updatePlayerInfo { it.copy(showCurrentTime = checked) }
    })
    SettingsToggleRow("自定义文字", "在信息行末尾追加自定义文字", playerInfo.showCustomText, { checked ->
        updatePlayerInfo { it.copy(showCustomText = checked) }
    })
    SettingsTextFieldRow(
        value = playerInfo.customText,
        onValueChange = { text -> updatePlayerInfo { it.copy(customText = text) } },
        placeholder = "输入自定义文本",
        enabled = playerInfo.showCustomText,
    )
}
