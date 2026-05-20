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
import com.mica.music.data.AppThemeMode
import com.mica.music.data.AppUiSettings
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.MusicLibrary
import com.mica.music.media.FfmpegRunner
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsToggleRow
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaPreset
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.micaBackground
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

@Composable
fun SettingsScreen(
    library: MusicLibrary,
    uiSettings: AppUiSettings,
    onBack: () -> Unit,
    onOpenEqualizer: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()

    var includeNonMusic by remember { mutableStateOf(com.mica.music.data.AppPreferences.includeNonMusicAudio(context)) }
    var deepProbe by remember { mutableStateOf(com.mica.music.data.AppPreferences.deepMetadataProbe(context)) }
    var minDurationSec by remember { mutableIntStateOf(com.mica.music.data.AppPreferences.minTrackDurationSec(context)) }

    val ffmpegReady = remember { FfmpegRunner.hasEmbeddedBinary(context) }

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
        if (granted) scope.launch { library.scanDeviceWide() }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        library.setLibraryFolder(uri)
        scope.launch { library.scanLibraryFolder() }
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
        scope.launch { library.rescan() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .micaBackground(MicaPreset.Fog)
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
                    -> "在封面下缘显示细进度条，并隐藏下方时间与常规进度条"
                    else -> "仅「主题色」「封面模糊」下生效；当前背景下仍使用常规进度条"
                },
                checked = uiSettings.coverEdgeProgress,
                onCheckedChange = { uiSettings.updateCoverEdgeProgress(it) },
            )

            SettingsToggleRow(
                title = "下半屏沉浸",
                subtitle = "封面以下仅显示歌名与歌手并居中；点击播放/暂停，长按歌名区域可开关",
                checked = uiSettings.playerImmersiveLower,
                onCheckedChange = { uiSettings.updatePlayerImmersiveLower(it) },
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

            SettingsSectionTitle("播放")

            SettingsActionRow(
                title = "均衡器",
                subtitle = "系统频段调节；播放中进入可加载预设与自定义曲线",
                onClick = onOpenEqualizer,
            )

            Spacer(Modifier.height(HifiSpacing.lg))

            SettingsSectionTitle("关于")

            SettingsActionRow(
                title = "FFmpeg（软件解码）",
                subtitle = if (ffmpegReady) {
                    "已打包 arm64；全部曲目 FFmpeg → PCM → AudioTrack"
                } else {
                    "未检测到二进制，请运行 scripts\\build-ffmpeg-arm64.ps1"
                },
                onClick = {},
                enabled = false,
            )

            SettingsActionRow(
                title = "版本",
                subtitle = "Mica Music 0.1.0 · arm64-v8a",
                onClick = {},
                enabled = false,
            )

            Spacer(Modifier.height(HifiSpacing.xxl))
        }
    }
}
