package com.mica.music.ui.screens

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mica.music.data.AppAccentColor
import com.mica.music.data.AppThemeMode
import com.mica.music.data.AppUiSettings
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.SongListInfoVisibility
import com.mica.music.data.MusicLibrary
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsTextFieldRow
import com.mica.music.ui.components.SettingsToggleRow
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaPreset
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.micaAppBackground
import com.mica.music.util.openAppSettings
import kotlinx.coroutines.launch

private val DurationChoices = listOf(
    0 to "不限",
    15 to "≥15秒",
    30 to "≥30秒",
    60 to "≥1分",
    120 to "≥2分",
)

private val ThemeChoices = listOf(
    AppThemeMode.SYSTEM.ordinal to "跟随系统",
    AppThemeMode.LIGHT.ordinal to "浅色",
    AppThemeMode.DARK.ordinal to "深色",
)

private val PlayerLowerBgChoices = PlayerLowerBackgroundMode.entries.map {
    it.ordinal to it.settingsLabel
}

private val MiniPlayerStyleChoices = MiniPlayerStyle.entries.map {
    it.ordinal to it.settingsLabel
}

private val AccentColorChoices = AppAccentColor.entries.map {
    it.ordinal to it.settingsLabel
}

private val MicaBackgroundChoices = MicaPreset.entries.map {
    it.ordinal to it.settingsLabel
}

private val CoverDisplayChoices = CoverDisplayMode.entries.map {
    it.ordinal to it.settingsLabel
}

private val PlayerCoverFlowChoices = PlayerCoverFlowMode.entries.map {
    it.ordinal to it.settingsLabel
}

@Composable
fun SettingsScreen(
    library: MusicLibrary,
    uiSettings: AppUiSettings,
    onBack: () -> Unit,
    onOpenMetadataDebug: () -> Unit,
    onOpenParticleCoverPreview: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()

    var includeNonMusic by remember { mutableStateOf(com.mica.music.data.AppPreferences.includeNonMusicAudio(context)) }
    var deepProbe by remember { mutableStateOf(com.mica.music.data.AppPreferences.deepMetadataProbe(context)) }
    var minDurationSec by remember { mutableIntStateOf(com.mica.music.data.AppPreferences.minTrackDurationSec(context)) }

    val audioPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        library.updatePermission(granted)
        if (granted) library.launchScanDeviceWide()
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        library.setLibraryFolder(uri)
        library.launchScanLibraryFolder()
    }

    fun requestRescan() {
        if (!library.hasLibraryFolder() && !library.hasAudioReadPermission()) {
            if (activity.shouldShowRequestPermissionRationale(audioPermission)) {
                permissionLauncher.launch(audioPermission)
            } else {
                openAppSettings(context)
            }
            return
        }
        library.launchRescan()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .micaAppBackground()
            .padding(contentPadding),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(HifiSize.topBarHeight)
                .padding(horizontal = HifiSpacing.sm),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(HifiSize.touchTarget)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = MicaTheme.colors.textPrimary,
                )
            }
            Text(
                text = "设置",
                style = MicaTheme.typography.display,
                color = MicaTheme.colors.textPrimary,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSectionTitle("外观")

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
                subtitle = "含「动态取色」：Android 12+ 跟随系统主题色",
                choices = AccentColorChoices,
                selectedValue = uiSettings.accentColor.ordinal,
                onSelect = { ordinal ->
                    uiSettings.updateAccentColor(AppAccentColor.entries[ordinal])
                },
            )

            SettingsChoiceRow(
                title = "云母背景",
                subtitle = "主页与各页面的渐变底色",
                choices = MicaBackgroundChoices,
                selectedValue = uiSettings.micaBackgroundPreset.ordinal,
                onSelect = { ordinal ->
                    uiSettings.updateMicaBackgroundPreset(MicaPreset.entries[ordinal])
                },
            )

            SettingsChoiceRow(
                title = "封面显示",
                subtitle = "原样比例：列表/歌词为正方框内完整显示；播放页大图可按比例；裁切填充：居中裁切",
                choices = CoverDisplayChoices,
                selectedValue = uiSettings.coverDisplayMode.ordinal,
                onSelect = { ordinal ->
                    uiSettings.updateCoverDisplayMode(CoverDisplayMode.entries[ordinal])
                },
            )

            SettingsChoiceRow(
                title = "播放页特殊主题",
                subtitle = "平行封面带 / 复古立体封面：播放页常驻封面流，并自动禁用原样比例",
                choices = PlayerCoverFlowChoices,
                selectedValue = uiSettings.playerCoverFlowMode.ordinal,
                onSelect = { ordinal ->
                    uiSettings.updatePlayerCoverFlowMode(PlayerCoverFlowMode.entries[ordinal])
                },
            )

            SettingsActionRow(
                title = "粒子封面预览",
                subtitle = "打开独立预览页，模拟切歌时专辑图分解与重组，不影响当前播放",
                onClick = onOpenParticleCoverPreview,
            )

            SettingsToggleRow(
                title = "隐藏状态栏",
                subtitle = "全屏显示内容；从屏幕顶部下滑可临时唤出状态栏",
                checked = uiSettings.hideStatusBar,
                onCheckedChange = { uiSettings.updateHideStatusBar(it) },
            )

            SettingsChoiceRow(
                title = "迷你播放栏",
                choices = MiniPlayerStyleChoices,
                selectedValue = uiSettings.miniPlayerStyle.ordinal,
                onSelect = { ordinal ->
                    uiSettings.updateMiniPlayerStyle(MiniPlayerStyle.entries[ordinal])
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

            SettingsToggleRow(
                title = "封面底边进度",
                subtitle = when (uiSettings.playerLowerBackground) {
                    PlayerLowerBackgroundMode.THEME,
                    PlayerLowerBackgroundMode.COVER_GLOW,
                    -> "开启后将进度条与频谱移到专辑图底边；关闭后使用普通布局"
                    else -> "标准主题仅「主题色」「封面模糊」下生效；特殊主题仍可在普通与底边布局间切换"
                },
                checked = uiSettings.coverEdgeProgress,
                onCheckedChange = { uiSettings.updateCoverEdgeProgress(it) },
            )

            SettingsToggleRow(
                title = "频谱条",
                subtitle = "显示随音乐跳动的频段条；位置跟随当前进度条布局",
                checked = uiSettings.spectrumEnabled,
                onCheckedChange = { uiSettings.updateSpectrumEnabled(it) },
            )

            SettingsToggleRow(
                title = "下半屏沉浸",
                subtitle = "封面以下仅显示歌名与歌手并居中；点击播放/暂停，长按歌名区域可开关",
                checked = uiSettings.playerImmersiveLower,
                onCheckedChange = { uiSettings.updatePlayerImmersiveLower(it) },
            )

            SettingsToggleRow(
                title = "分割双语歌词",
                subtitle = "将含细空格（U+2009 等）或 //、/、| 的行拆成上下两行；关闭后每行 LRC 保持一行",
                checked = uiSettings.lyricSplitEnabled,
                onCheckedChange = { uiSettings.updateLyricSplitEnabled(it) },
            )

            Spacer(Modifier.height(HifiSpacing.lg))

            SettingsSectionTitle("歌曲列表信息自定义")

            val songListInfo = uiSettings.songListInfoVisibility
            fun updateSongListInfo(transform: (SongListInfoVisibility) -> SongListInfoVisibility) {
                uiSettings.updateSongListInfoVisibility(transform(uiSettings.songListInfoVisibility))
            }

            SettingsToggleRow(
                title = "歌曲数量",
                subtitle = "显示「N 首歌曲」或未扫描提示",
                checked = songListInfo.showSongCount,
                onCheckedChange = { checked ->
                    updateSongListInfo { it.copy(showSongCount = checked) }
                },
            )

            SettingsToggleRow(
                title = "曲库大小",
                subtitle = "显示曲库占用空间",
                checked = songListInfo.showLibrarySize,
                onCheckedChange = { checked ->
                    updateSongListInfo { it.copy(showLibrarySize = checked) }
                },
            )

            SettingsToggleRow(
                title = "排序方式",
                subtitle = "显示当前歌曲列表排序",
                checked = songListInfo.showSortOrder,
                onCheckedChange = { checked ->
                    updateSongListInfo { it.copy(showSortOrder = checked) }
                },
            )

            SettingsToggleRow(
                title = "上次扫描时间",
                subtitle = "显示距上次扫描的时间；扫描进行中仍会显示进度",
                checked = songListInfo.showLastScanTime,
                onCheckedChange = { checked ->
                    updateSongListInfo { it.copy(showLastScanTime = checked) }
                },
            )

            SettingsToggleRow(
                title = "自定义",
                subtitle = "在信息栏末尾追加自定义文字",
                checked = songListInfo.showCustomText,
                onCheckedChange = { checked ->
                    updateSongListInfo { it.copy(showCustomText = checked) }
                },
            )

            SettingsTextFieldRow(
                value = songListInfo.customText,
                onValueChange = { text ->
                    updateSongListInfo { it.copy(customText = text) }
                },
                placeholder = "输入自定义文字",
                enabled = songListInfo.showCustomText,
            )

            Spacer(Modifier.height(HifiSpacing.lg))

            SettingsSectionTitle("扫描")

            SettingsActionRow(
                title = "曲库文件夹",
                subtitle = library.libraryFolderLabel?.let { "当前：$it" } ?: "未选择 · 通过系统文件选择器授权目录",
                onClick = { folderPickerLauncher.launch(null) },
                enabled = !library.isScanning,
            )

            SettingsChoiceRow(
                title = "最短曲目时长",
                subtitle = "过滤铃声、提示音等极短音频",
                choices = DurationChoices,
                selectedValue = minDurationSec,
                onSelect = { sec ->
                    minDurationSec = sec
                    com.mica.music.data.AppPreferences.setMinTrackDurationSec(context, sec)
                },
            )

            SettingsToggleRow(
                title = "纳入非「音乐」标记的音频",
                subtitle = "开启后可扫描到部分 m4a / ALAC（MediaStore 里 IS_MUSIC=0）",
                checked = includeNonMusic,
                onCheckedChange = {
                    includeNonMusic = it
                    com.mica.music.data.AppPreferences.setIncludeNonMusicAudio(context, it)
                },
            )

            SettingsToggleRow(
                title = "深度分析音质与封面",
                subtitle = "关闭后扫描更快，但格式/采样率/封面可能不准确",
                checked = deepProbe,
                onCheckedChange = {
                    deepProbe = it
                    com.mica.music.data.AppPreferences.setDeepMetadataProbe(context, it)
                },
            )

            SettingsActionRow(
                title = "元数据调试",
                subtitle = "逐首查看应用内字段、ID3/Vorbis、Retriever 与解析器结果",
                onClick = onOpenMetadataDebug,
                enabled = library.songs.isNotEmpty(),
            )

            SettingsActionRow(
                title = "重新扫描曲库",
                subtitle = when {
                    library.isScanning -> library.scanProgressLabel ?: "扫描中…"
                    library.hasLibraryFolder() && !library.hasAudioReadPermission() ->
                        "将扫描「${library.libraryFolderLabel}」"
                    !library.hasAudioReadPermission() && !library.hasLibraryFolder() ->
                        "需要授予读取音频权限，或先选择曲库文件夹"
                    library.lastScanAtMs == null -> "尚未扫描"
                    else -> "共 ${library.songs.size} 首 · ${library.totalSizeMb} MB"
                },
                onClick = { requestRescan() },
                enabled = !library.isScanning,
            )

            SettingsActionRow(
                title = "系统权限与应用信息",
                subtitle = "管理存储/音频读取、通知等权限",
                onClick = { openAppSettings(context) },
            )

            Spacer(Modifier.height(HifiSpacing.lg))

            Spacer(Modifier.height(HifiSpacing.xxl))
        }
    }
}
