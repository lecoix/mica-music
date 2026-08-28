package com.mica.music.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mica.music.data.AppHiResBadgeImporter
import com.mica.music.data.AppUiSettings
import com.mica.music.data.CompactLyricsLineMode
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.HiResBadgeStyle
import com.mica.music.data.PlaybackContentColorMode
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.PlayerInfoVisibility
import com.mica.music.data.usesCompactLyricsLinePreference
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsToggleRow
import com.mica.music.ui.components.SettingsTextFieldRow
import com.mica.music.ui.screens.player.ParticleCoverThemePolicy
import com.mica.music.ui.theme.HifiSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PlaybackSettingsPanel(
    uiSettings: AppUiSettings,
    canOpenCustomPlayerLayoutEditor: Boolean = true,
    onOpenCustomPlayerLayoutEditor: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val badgeImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AppHiResBadgeImporter.importBadge(context, uri)
            }
            result.path?.let { path ->
                uiSettings.updateHiResBadgeCustomImagePath(path)
                uiSettings.updateHiResBadgeStyle(HiResBadgeStyle.CUSTOM_IMAGE)
            }
            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
        }
    }

    SettingsSectionTitle("主题")

    SettingsChoiceRow(
        title = "播放页特殊主题",
        subtitle = "选择播放页的封面与下半区呈现方式；主题会决定下方可用的专属选项",
        choices = PlayerCoverFlowChoices,
        selectedValue = uiSettings.playerCoverFlowMode.ordinal,
        onSelect = { ordinal ->
            uiSettings.updatePlayerCoverFlowMode(PlayerCoverFlowMode.entries[ordinal])
        },
    )

    Spacer(Modifier.height(HifiSpacing.lg))
    SettingsSectionTitle("封面与播放页")

    if (!ParticleCoverThemePolicy.forcesSquareCrop(uiSettings.playerCoverFlowMode)) {
        SettingsChoiceRow(
            title = "封面显示",
            subtitle = "原样比例：列表/歌词页为正方框内完整显示；播放页大图可按比例；裁切填充：居中裁切",
            choices = CoverDisplayChoices,
            selectedValue = uiSettings.coverDisplayMode.ordinal,
            onSelect = { ordinal ->
                uiSettings.updateCoverDisplayMode(CoverDisplayMode.entries[ordinal])
            },
        )
    }

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
        subtitle = "信息行、歌名、艺人、专辑、进度条与底部五个按钮；动态取色：稳定主色 + 语义色阶（浅色更鲜艳）",
        choices = PlaybackContentColorChoices,
        selectedValue = uiSettings.playerPageTextColorMode.ordinal,
        onSelect = { ordinal ->
            uiSettings.updatePlayerPageTextColorMode(
                PlaybackContentColorMode.entries[ordinal],
            )
        },
    )

    if (uiSettings.playerCoverFlowMode == PlayerCoverFlowMode.STANDARD) {
        SettingsToggleRow(
            title = "音乐 MV",
            subtitle = "同目录同文件名 MP4 只显示画面，音乐仍是唯一声音来源；切换从下一首歌曲生效",
            checked = uiSettings.musicVideoEnabled,
            onCheckedChange = uiSettings::updateMusicVideoEnabled,
        )
        SettingsToggleRow(
            title = "视频专辑封面",
            subtitle = "开启后重扫文件夹曲库，匹配歌曲同目录内与专辑同名的 MP4，仅在标准播放页静音循环播放",
            checked = uiSettings.videoAlbumCoverEnabled,
            onCheckedChange = uiSettings::updateVideoAlbumCoverEnabled,
        )
    }

    if (uiSettings.playerCoverFlowMode == PlayerCoverFlowMode.CUSTOM_STANDARD) {
        SettingsActionRow(
            title = "进入播放页布局编辑",
            subtitle = if (canOpenCustomPlayerLayoutEditor) {
                "打开播放页，直接拖动、双指缩放或设置六个元素的显隐"
            } else {
                "请先播放一首歌曲"
            },
            enabled = canOpenCustomPlayerLayoutEditor,
            onClick = onOpenCustomPlayerLayoutEditor,
        )
        SettingsToggleRow(
            title = "点击封面暂停/播放",
            subtitle = "仅在自定义标准主题生效；不影响左右滑动切歌与长按菜单",
            checked = uiSettings.customStandardCoverTapPlayPause,
            onCheckedChange = uiSettings::updateCustomStandardCoverTapPlayPause,
        )
    }

    val coverEdgeProgressAvailable = uiSettings.playerCoverFlowMode != PlayerCoverFlowMode.CUSTOM_STANDARD &&
        !uiSettings.playerCoverFlowMode.usesPhotoStack &&
        (uiSettings.playerCoverFlowMode != PlayerCoverFlowMode.STANDARD ||
            uiSettings.playerLowerBackground == PlayerLowerBackgroundMode.THEME ||
            uiSettings.playerLowerBackground.usesBlurredArtwork)
    if (coverEdgeProgressAvailable) {
        SettingsToggleRow(
            title = "封面底边进度",
            subtitle = when {
                uiSettings.playerCoverFlowMode == PlayerCoverFlowMode.PARTICLE_COVER ->
                    "开启后隐藏进度条与频谱；关闭后使用普通布局"
                uiSettings.playerCoverFlowMode == PlayerCoverFlowMode.STANDARD ->
                    "开启后将进度条与频谱移到专辑图底边；关闭后使用普通布局"
                else ->
                    "当前特殊主题支持将进度条与频谱移到专辑图底边；关闭后使用普通布局"
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

    if (uiSettings.playerCoverFlowMode.supportsImmersiveLower) {
        SettingsToggleRow(
            title = "下半屏沉浸",
            subtitle = "封面以下仅保留主题必要信息；拍立得回忆会把歌名、歌手与进度收进相纸，轻点播放/暂停、长按照片退出",
            checked = uiSettings.playerImmersiveLower,
            onCheckedChange = uiSettings::updatePlayerImmersiveLower,
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

    Spacer(Modifier.height(HifiSpacing.lg))
    SettingsSectionTitle("Hi-Res 标志")

    SettingsChoiceRow(
        title = "标志样式",
        subtitle = "Hi-Res 曲目在信息行右侧显示的标志",
        choices = HiResBadgeStyleChoices,
        selectedValue = uiSettings.hiResBadgeStyle.ordinal,
        onSelect = { ordinal ->
            uiSettings.updateHiResBadgeStyle(HiResBadgeStyle.entries[ordinal])
        },
    )

    if (uiSettings.hiResBadgeStyle == HiResBadgeStyle.CUSTOM_IMAGE) {
        SettingsActionRow(
            title = "选择图片",
            subtitle = if (uiSettings.hiResBadgeCustomImagePath == null) {
                "最高 24dp 视觉高度；上下各最多溢出 4dp，信息行布局不变"
            } else {
                "已设置自定义图片"
            },
            onClick = {
                badgeImagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )
        SettingsActionRow(
            title = "清除自定义图片",
            subtitle = "恢复为默认圆点 + Hi-Res 文字",
            enabled = uiSettings.hiResBadgeCustomImagePath != null,
            onClick = {
                AppHiResBadgeImporter.clearBadge(context)
                uiSettings.updateHiResBadgeCustomImagePath(null)
                uiSettings.updateHiResBadgeStyle(HiResBadgeStyle.DEFAULT)
                Toast.makeText(context, "已恢复默认 Hi-Res 标志", Toast.LENGTH_SHORT).show()
            },
        )
    }
}
