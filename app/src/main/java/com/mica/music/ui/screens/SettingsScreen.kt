package com.mica.music.ui.screens

import android.Manifest
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.AppAccentColor
import com.mica.music.data.AppThemeMode
import com.mica.music.data.AppUiSettings
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsPageAlignment
import com.mica.music.data.MAX_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.MIN_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.SongListInfoVisibility
import com.mica.music.data.MusicLibrary
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsDropdownRow
import com.mica.music.ui.components.SettingsNavigationRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsTextFieldRow
import com.mica.music.ui.components.SettingsToggleRow
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaPreset
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.micaAppBackground
import com.mica.music.util.openAppSettings
import java.util.Locale

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

private val PlayerLowerBgChoices = PlayerLowerBackgroundMode.entries
    .filterNot { it == PlayerLowerBackgroundMode.DYNAMIC_LIGHT }
    .map { it.ordinal to it.settingsLabel }

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

private val LyricsPageAlignmentChoices = LyricsPageAlignment.entries.map {
    it.ordinal to it.settingsLabel
}

private val LyricsBilingualDisplayChoices = LyricsBilingualDisplayMode.entries.map {
    it.ordinal to it.settingsLabel
}

private val LyricsPageFontSizeChoices = (MIN_LYRICS_PAGE_FONT_SIZE_SP..MAX_LYRICS_PAGE_FONT_SIZE_SP)
    .map { it to "$it sp" }

private enum class SettingsCategory(
    val title: String,
    val subtitle: String,
) {
    APPEARANCE(
        title = "外观与主题",
        subtitle = "主题、强调色、云母背景、状态栏、迷你播放栏",
    ),
    PLAYBACK(
        title = "播放页",
        subtitle = "封面、播放页背景、特殊主题、频谱",
    ),
    LYRICS(
        title = "歌词页",
        subtitle = "双语拆分、逐字填充、歌词页样式",
    ),
    LIST_INFO(
        title = "列表信息",
        subtitle = "歌曲列表统计与自定义文字",
    ),
    LIBRARY(
        title = "曲库与扫描",
        subtitle = "曲库文件夹、重新扫描、时长过滤",
    ),
    ADVANCED(
        title = "高级与调试",
        subtitle = "扫描兼容、元数据调试、系统权限",
    ),
}

@Composable
fun SettingsScreen(
    library: MusicLibrary,
    uiSettings: AppUiSettings,
    onBack: () -> Unit,
    onOpenMetadataDebug: () -> Unit,
    onOpenParticleCoverPreview: () -> Unit,
    onOpenPhotoStackShadowPreview: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    bottomContentClearance: Dp = 0.dp,
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity

    var includeNonMusic by remember { mutableStateOf(com.mica.music.data.AppPreferences.includeNonMusicAudio(context)) }
    var deepProbe by remember { mutableStateOf(com.mica.music.data.AppPreferences.deepMetadataProbe(context)) }
    var minDurationSec by remember { mutableIntStateOf(com.mica.music.data.AppPreferences.minTrackDurationSec(context)) }
    var showCustomAccentDialog by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<SettingsCategory?>(null) }

    BackHandler(enabled = selectedCategory != null) {
        selectedCategory = null
    }

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

    if (showCustomAccentDialog) {
        CustomAccentColorDialog(
            initialColorArgb = uiSettings.customAccentColorArgb,
            onDismiss = { showCustomAccentDialog = false },
            onConfirm = { colorArgb ->
                uiSettings.updateCustomAccentColorArgb(colorArgb)
                showCustomAccentDialog = false
            },
        )
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
            IconButton(
                onClick = {
                    if (selectedCategory == null) {
                        onBack()
                    } else {
                        selectedCategory = null
                    }
                },
                modifier = Modifier.size(HifiSize.touchTarget),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = MicaTheme.colors.textPrimary,
                )
            }
            Text(
                text = selectedCategory?.title ?: "设置",
                style = MicaTheme.typography.display,
                color = MicaTheme.colors.textPrimary,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            if (selectedCategory == null) {
                SettingsSectionTitle("浏览设置")
                SettingsCategory.entries.forEach { category ->
                    SettingsNavigationRow(
                        title = category.title,
                        subtitle = category.subtitle,
                        onClick = { selectedCategory = category },
                    )
                }
            } else {
                when (selectedCategory) {
                    SettingsCategory.APPEARANCE -> {
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
                                    showCustomAccentDialog = true
                                } else {
                                    uiSettings.updateAccentColor(accent)
                                }
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
                    }

                    SettingsCategory.PLAYBACK -> {
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

                    SettingsCategory.LYRICS -> {
                        SettingsSectionTitle("歌词页")

                        SettingsToggleRow(
                            title = "分割双语歌词",
                            subtitle = "将含细空格（U+2009 等）或 //、/、| 的行拆成上下两行；关闭后每行 LRC 保持一行",
                            checked = uiSettings.lyricSplitEnabled,
                            onCheckedChange = { uiSettings.updateLyricSplitEnabled(it) },
                        )

                        SettingsChoiceRow(
                            title = "双语歌词显示",
                            subtitle = "仅在分割双语歌词开启、且当前歌词行可拆分时生效",
                            choices = LyricsBilingualDisplayChoices,
                            selectedValue = uiSettings.lyricsBilingualDisplayMode.ordinal,
                            onSelect = { ordinal ->
                                uiSettings.updateLyricsBilingualDisplayMode(
                                    LyricsBilingualDisplayMode.entries[ordinal],
                                )
                            },
                        )

                        SettingsToggleRow(
                            title = "强制使用逐字歌词样式",
                            subtitle = "对没有逐字时间轴的歌词，当前句按本句到下一句的播放进度从左到右填充",
                            checked = uiSettings.lyricLineFillEnabled,
                            onCheckedChange = { uiSettings.updateLyricLineFillEnabled(it) },
                        )

                        SettingsChoiceRow(
                            title = "歌词页对齐",
                            choices = LyricsPageAlignmentChoices,
                            selectedValue = uiSettings.lyricsPageAlignment.ordinal,
                            onSelect = { ordinal ->
                                uiSettings.updateLyricsPageAlignment(LyricsPageAlignment.entries[ordinal])
                            },
                        )

                        SettingsDropdownRow(
                            title = "歌词页字号",
                            choices = LyricsPageFontSizeChoices,
                            selectedValue = uiSettings.lyricsPageFontSizeSp,
                            onSelect = { uiSettings.updateLyricsPageFontSizeSp(it) },
                        )

                        SettingsToggleRow(
                            title = "歌词页沉浸模式",
                            subtitle = "开启后歌词页隐藏进度条和底部五个按钮；在歌词页长按播放按钮也可切换",
                            checked = uiSettings.lyricsPageImmersive,
                            onCheckedChange = { uiSettings.updateLyricsPageImmersive(it) },
                        )
                    }

                    SettingsCategory.LIST_INFO -> {
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
                            placeholder = "输入自定义文本",
                            enabled = songListInfo.showCustomText,
                        )
                    }

                    SettingsCategory.LIBRARY -> {
                        SettingsSectionTitle("曲库与扫描")

                        SettingsActionRow(
                            title = "曲库文件夹",
                            subtitle = library.libraryFolderLabel?.let { "当前：$it" } ?: "未选择 · 通过系统文件选择器授权目录",
                            onClick = { folderPickerLauncher.launch(null) },
                            enabled = !library.isScanning,
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
                    }

                    SettingsCategory.ADVANCED -> {
                        SettingsSectionTitle("高级扫描与调试")

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
                            subtitle = "我也不知道这玩意还有啥用，总之先别关",
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
                            title = "系统权限与应用信息",
                            subtitle = "管理存储/音频读取、通知等权限",
                            onClick = { openAppSettings(context) },
                        )
                    }

                    else -> Unit
                }
            }

            Spacer(Modifier.height(HifiSpacing.lg))

            Spacer(Modifier.height(HifiSpacing.xxl + bottomContentClearance))
        }
    }
}

@Composable
private fun CustomAccentColorDialog(
    initialColorArgb: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initialHsv = remember(initialColorArgb) {
        FloatArray(3).also { AndroidColor.colorToHSV(initialColorArgb, it) }
    }
    var hue by remember(initialColorArgb) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember(initialColorArgb) { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember(initialColorArgb) { mutableFloatStateOf(initialHsv[2]) }
    var hexValue by remember(initialColorArgb) { mutableStateOf(formatAccentHex(initialColorArgb)) }
    val parsedColorArgb = parseAccentHex(hexValue)
    val previewColorArgb = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness))
    val previewColor = Color(previewColorArgb)

    fun setHsv(newHue: Float = hue, newSaturation: Float = saturation, newBrightness: Float = brightness) {
        hue = newHue.coerceIn(0f, 360f)
        saturation = newSaturation.coerceIn(0f, 1f)
        brightness = newBrightness.coerceIn(0f, 1f)
        hexValue = formatAccentHex(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        title = {
            Text(
                text = "自定义强调色",
                style = MicaTheme.typography.titleMd,
                color = MicaTheme.colors.textPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(HifiSpacing.md)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(HifiSpacing.md),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(previewColor)
                            .border(1.dp, MicaTheme.colors.divider),
                    )
                    Text(
                        text = formatAccentHex(previewColorArgb),
                        style = MicaTheme.typography.monoSm,
                        color = MicaTheme.colors.textSecondary,
                    )
                }

                HsvColorSlider(
                    label = "色相",
                    value = hue,
                    valueRange = 0f..360f,
                    valueText = "${hue.toInt()}°",
                    colors = hueGradientColors(),
                    onValueChange = { setHsv(newHue = it) },
                )
                HsvColorSlider(
                    label = "饱和度",
                    value = saturation,
                    valueRange = 0f..1f,
                    valueText = "${(saturation * 100).toInt()}%",
                    colors = listOf(
                        Color(AndroidColor.HSVToColor(floatArrayOf(hue, 0f, brightness))),
                        Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, brightness))),
                    ),
                    onValueChange = { setHsv(newSaturation = it) },
                )
                HsvColorSlider(
                    label = "明度",
                    value = brightness,
                    valueRange = 0f..1f,
                    valueText = "${(brightness * 100).toInt()}%",
                    colors = listOf(
                        Color.Black,
                        Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, 1f))),
                    ),
                    onValueChange = { setHsv(newBrightness = it) },
                )

                OutlinedTextField(
                    value = hexValue,
                    onValueChange = { value ->
                        hexValue = value
                        parseAccentHex(value)?.let { colorArgb ->
                            val hsv = FloatArray(3)
                            AndroidColor.colorToHSV(colorArgb, hsv)
                            hue = hsv[0]
                            saturation = hsv[1]
                            brightness = hsv[2]
                        }
                    },
                    singleLine = true,
                    isError = parsedColorArgb == null,
                    label = {
                        Text(
                            text = "#RRGGBB",
                            style = MicaTheme.typography.caption,
                        )
                    },
                    supportingText = {
                        if (parsedColorArgb == null) {
                            Text(
                                text = "请输入 6 位十六进制颜色",
                                style = MicaTheme.typography.caption,
                            )
                        }
                    },
                    textStyle = MicaTheme.typography.bodyMd.copy(color = MicaTheme.colors.textPrimary),
                    shape = RectangleShape,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsedColorArgb != null,
                onClick = { onConfirm(previewColorArgb) },
            ) {
                Text("应用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun HsvColorSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    colors: List<Color>,
    onValueChange: (Float) -> Unit,
) {
    val min = valueRange.start
    val max = valueRange.endInclusive.coerceAtLeast(min + 0.001f)
    val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
    var widthPx by remember { mutableFloatStateOf(0f) }

    fun positionToValue(x: Float): Float {
        if (widthPx <= 0f) return value
        return min + (x / widthPx).coerceIn(0f, 1f) * (max - min)
    }

    Column(verticalArrangement = Arrangement.spacedBy(HifiSpacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textSecondary,
            )
            Text(
                text = valueText,
                style = MicaTheme.typography.monoSm,
                color = MicaTheme.colors.textTertiary,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(min, max) {
                    detectTapGestures { offset -> onValueChange(positionToValue(offset.x)) }
                }
                .pointerInput(min, max) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onValueChange(positionToValue(change.position.x))
                    }
                },
        ) {
            val centerY = size.height / 2f
            drawLine(
                brush = Brush.horizontalGradient(colors),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 10.dp.toPx(),
                cap = StrokeCap.Butt,
            )
            val thumbSize = 16.dp.toPx()
            drawRect(
                color = Color.White,
                topLeft = Offset(
                    x = (size.width * fraction - thumbSize / 2f).coerceIn(0f, size.width - thumbSize),
                    y = centerY - thumbSize / 2f,
                ),
                size = Size(thumbSize, thumbSize),
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.28f),
                topLeft = Offset(
                    x = (size.width * fraction - thumbSize / 2f).coerceIn(0f, size.width - thumbSize),
                    y = centerY - thumbSize / 2f,
                ),
                size = Size(thumbSize, thumbSize),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
            )
        }
    }
}

private fun formatAccentHex(colorArgb: Int): String =
    String.format(Locale.US, "#%06X", colorArgb and 0x00FFFFFF)

private fun parseAccentHex(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { it !in '0'..'9' && it.uppercaseChar() !in 'A'..'F' }) {
        return null
    }
    return (0xFF000000 or hex.toLong(16)).toInt()
}

private fun hueGradientColors(): List<Color> =
    listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { hue ->
        Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    }
