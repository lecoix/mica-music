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
import com.mica.music.ui.components.SettingsNavigationRow
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
    onOpenPlayerInfo: () -> Unit = {},
) {
    SettingsSectionTitle("主题")

    SettingsChoiceRow(
        title = "播放页特殊主题",
        modifier = settingsSearchAnchor("playback.theme"),
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
            modifier = settingsSearchAnchor("playback.cover-display"),
            subtitle = "原样比例保留完整封面；裁切填充居中裁切",
            choices = CoverDisplayChoices,
            selectedValue = uiSettings.coverDisplayMode.ordinal,
            onSelect = { ordinal ->
                uiSettings.updateCoverDisplayMode(CoverDisplayMode.entries[ordinal])
            },
        )
    }

    SettingsChoiceRow(
        title = "播放页背景",
        modifier = settingsSearchAnchor("playback.lower-background"),
        choices = PlayerLowerBgChoices,
        selectedValue = uiSettings.playerLowerBackground.ordinal,
        onSelect = { ordinal ->
            uiSettings.updatePlayerLowerBackground(PlayerLowerBackgroundMode.entries[ordinal])
        },
    )

    SettingsChoiceRow(
        title = "播放页 UI 颜色",
        modifier = settingsSearchAnchor("playback.content-color"),
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
            modifier = settingsSearchAnchor("playback.music-video"),
            subtitle = "匹配同目录同名 MP4；从下一首生效",
            checked = uiSettings.musicVideoEnabled,
            onCheckedChange = uiSettings::updateMusicVideoEnabled,
        )
        SettingsToggleRow(
            title = "视频专辑封面",
            modifier = settingsSearchAnchor("playback.video-album-cover"),
            subtitle = "匹配同目录专辑同名 MP4；开启后需重扫曲库",
            checked = uiSettings.videoAlbumCoverEnabled,
            onCheckedChange = uiSettings::updateVideoAlbumCoverEnabled,
        )
    }

    if (uiSettings.playerCoverFlowMode == PlayerCoverFlowMode.CUSTOM_STANDARD) {
        SettingsActionRow(
            title = "进入播放页布局编辑",
            modifier = settingsSearchAnchor("playback.custom-layout"),
            subtitle = if (canOpenCustomPlayerLayoutEditor) {
                "拖动、缩放、显隐；选中封面可设阴影和点击播放"
            } else {
                "请先播放一首歌曲"
            },
            enabled = canOpenCustomPlayerLayoutEditor,
            onClick = onOpenCustomPlayerLayoutEditor,
        )
    }

    val coverEdgeProgressAvailable = uiSettings.playerCoverFlowMode != PlayerCoverFlowMode.CUSTOM_STANDARD &&
        !uiSettings.playerCoverFlowMode.usesPhotoStack &&
        (uiSettings.playerCoverFlowMode != PlayerCoverFlowMode.STANDARD ||
            uiSettings.playerLowerBackground.supportsStandardCoverEdgeProgress)
    if (coverEdgeProgressAvailable) {
        SettingsToggleRow(
            title = "封面底边进度",
            modifier = settingsSearchAnchor("playback.cover-edge-progress"),
            subtitle = when {
                uiSettings.playerCoverFlowMode == PlayerCoverFlowMode.PARTICLE_COVER ->
                    "当前主题下开启后将隐藏进度条与频谱"
                uiSettings.playerCoverFlowMode == PlayerCoverFlowMode.STANDARD ->
                    "开启后将进度条与频谱移到专辑图底边"
                else ->
                    "当前特殊主题支持将进度条与频谱移到专辑图底边"
            },
            checked = uiSettings.coverEdgeProgress,
            onCheckedChange = { uiSettings.updateCoverEdgeProgress(it) },
        )
    }

    SettingsToggleRow(
        title = "播放时屏幕常亮",
        modifier = settingsSearchAnchor("playback.keep-screen-on"),
        checked = uiSettings.keepScreenOnWhenPlaying,
        onCheckedChange = { uiSettings.updateKeepScreenOnWhenPlaying(it) },
    )

    if (uiSettings.playerCoverFlowMode.supportsImmersiveLower) {
        SettingsToggleRow(
            title = "沉浸模式",
            modifier = settingsSearchAnchor("playback.immersive-lower"),
            subtitle = "标准主题在封面以下仅保留标题与艺术家；拍立得回忆会把歌名、歌手与进度收进相纸，轻点播放/暂停、长按照片退出",
            checked = uiSettings.playerImmersiveLower,
            onCheckedChange = uiSettings::updatePlayerImmersiveLower,
        )
        if (uiSettings.playerCoverFlowMode.usesPhotoStack) {
            SettingsToggleRow(
                title = "沉浸时标题显示歌词",
                modifier = settingsSearchAnchor("playback.photo-stack-immersive-lyrics"),
                subtitle = "歌词替换相纸歌名；译文作为副标题",
                checked = uiSettings.photoStackImmersiveLyricsEnabled,
                onCheckedChange = uiSettings::updatePhotoStackImmersiveLyricsEnabled,
            )
        }
    }

    if (uiSettings.playerCoverFlowMode.usesCompactLyricsLinePreference()) {
        SettingsChoiceRow(
            title = "折叠歌词行数",
            modifier = settingsSearchAnchor("playback.compact-lyrics"),
            subtitle = "自动：按可用高度在一行与三行间切换",
            choices = CompactLyricsLineModeChoices,
            selectedValue = uiSettings.compactLyricsLineMode.ordinal,
            onSelect = { ordinal ->
                uiSettings.updateCompactLyricsLineMode(CompactLyricsLineMode.entries[ordinal])
            },
        )
    }

    SettingsToggleRow(
        title = "隐藏歌名括号内容",
        modifier = settingsSearchAnchor("playback.strip-title-parentheses"),
        subtitle = "不会修改曲库原始标题",
        checked = uiSettings.stripSongTitleParentheses,
        onCheckedChange = { uiSettings.updateStripSongTitleParentheses(it) },
    )

    Spacer(Modifier.height(HifiSpacing.lg))

    SettingsSectionTitle("动态效果")

    SettingsToggleRow(
        title = "频谱条",
        modifier = settingsSearchAnchor("playback.spectrum"),
        checked = uiSettings.spectrumEnabled,
        onCheckedChange = { uiSettings.updateSpectrumEnabled(it) },
    )

    Spacer(Modifier.height(HifiSpacing.lg))
    SettingsNavigationRow(
        title = "信息行",
        subtitle = playerInfoSummary(uiSettings.playerInfoVisibility),
        onClick = onOpenPlayerInfo,
    )
}

internal fun playerInfoSummary(info: PlayerInfoVisibility): String = buildList {
    if (info.showFormat) add("格式")
    if (info.showSampleRate) add("位深/采样率")
    if (info.showBitrate) add("比特率")
    if (info.showPlaybackSpeed) add("速度")
    if (info.showPlaybackPitch) add("音高")
    if (info.showCurrentTime) add("时间")
    if (info.showCustomText) add("自定义文字")
}.joinToString(" · ").ifEmpty { "未显示参数 · Hi-Res 标志" }

@Composable
internal fun PlayerInfoSettingsPanel(uiSettings: AppUiSettings) {
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

    SettingsSectionTitle("信息行内容")

    val playerInfo = uiSettings.playerInfoVisibility
    fun updatePlayerInfo(transform: (PlayerInfoVisibility) -> PlayerInfoVisibility) {
        uiSettings.updatePlayerInfoVisibility(transform(uiSettings.playerInfoVisibility))
    }
    SettingsToggleRow("格式", "显示容器格式，如 FLAC、MP3", playerInfo.showFormat, { checked ->
        updatePlayerInfo { it.copy(showFormat = checked) }
    }, modifier = settingsSearchAnchor("playback.info-format"))
    SettingsToggleRow("位深/采样率", "显示如 24bit/96kHz", playerInfo.showSampleRate, { checked ->
        updatePlayerInfo { it.copy(showSampleRate = checked) }
    }, modifier = settingsSearchAnchor("playback.info-sample-rate"))
    SettingsToggleRow("比特率", "显示如 320 kbps", playerInfo.showBitrate, { checked ->
        updatePlayerInfo { it.copy(showBitrate = checked) }
    }, modifier = settingsSearchAnchor("playback.info-bitrate"))
    SettingsToggleRow("速度", "显示当前播放速度，如 1.25x", playerInfo.showPlaybackSpeed, { checked ->
        updatePlayerInfo { it.copy(showPlaybackSpeed = checked) }
    }, modifier = settingsSearchAnchor("playback.info-speed"))
    SettingsToggleRow("音高", "显示当前变调，如 +2 半音", playerInfo.showPlaybackPitch, { checked ->
        updatePlayerInfo { it.copy(showPlaybackPitch = checked) }
    }, modifier = settingsSearchAnchor("playback.info-pitch"))
    SettingsToggleRow("时间", "显示当前系统时间", playerInfo.showCurrentTime, { checked ->
        updatePlayerInfo { it.copy(showCurrentTime = checked) }
    }, modifier = settingsSearchAnchor("playback.info-time"))
    SettingsToggleRow("自定义文字", "追加自定义文字", playerInfo.showCustomText, { checked ->
        updatePlayerInfo { it.copy(showCustomText = checked) }
    }, modifier = settingsSearchAnchor("playback.info-custom-text"))
    if (playerInfo.showCustomText) {
        SettingsTextFieldRow(
            value = playerInfo.customText,
            onValueChange = { text -> updatePlayerInfo { it.copy(customText = text) } },
            placeholder = "输入自定义文本",
            enabled = playerInfo.showCustomText,
        )
    }

    Spacer(Modifier.height(HifiSpacing.lg))
    SettingsChoiceRow(
        title = "Hi-Res 标志",
        modifier = settingsSearchAnchor("playback.hires-badge"),
        subtitle = if (
            uiSettings.hiResBadgeStyle == HiResBadgeStyle.CUSTOM_IMAGE &&
            uiSettings.hiResBadgeCustomImagePath != null
        ) {
            "已设置自定义图片"
        } else {
            null
        },
        choices = buildList {
            addAll(HiResBadgeStyleChoices)
            if (uiSettings.hiResBadgeCustomImagePath != null) {
                add(100 to "移除图片")
            }
        },
        selectedValue = uiSettings.hiResBadgeStyle.ordinal,
        onSelect = { value ->
            when (value) {
                100 -> {
                    uiSettings.updateHiResBadgeCustomImagePath(null)
                    uiSettings.updateHiResBadgeStyle(HiResBadgeStyle.DEFAULT)
                    Toast.makeText(context, "已取消使用自定义 Hi-Res 图片", Toast.LENGTH_SHORT).show()
                }
                HiResBadgeStyle.CUSTOM_IMAGE.ordinal -> {
                    badgeImagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }
                else -> uiSettings.updateHiResBadgeStyle(HiResBadgeStyle.entries[value])
            }
        },
    )
}
