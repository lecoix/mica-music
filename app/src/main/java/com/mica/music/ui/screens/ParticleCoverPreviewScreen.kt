package com.mica.music.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.MusicLibrary
import com.mica.music.data.ParticleCoverTuning
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import com.mica.music.imaging.CoverDecodeTarget
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.screens.player.view.ParticleCoverHost
import com.mica.music.ui.screens.player.view.ParticleCoverPreviewOptions
import com.mica.music.ui.screens.player.view.ParticleCoverThemePreset
import com.mica.music.ui.screens.player.view.ThreeParticleCoverHost
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.coverColor
import com.mica.music.ui.theme.micaAppBackground
import kotlinx.coroutines.delay

@Composable
fun ParticleCoverPreviewScreen(
    library: MusicLibrary,
    savedTuning: ParticleCoverTuning,
    onSaveTuning: (ParticleCoverTuning) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    bottomContentClearance: Dp = 0.dp,
) {
    val previewSongs = remember(library.songs) {
        library.songs
            .filter { !it.albumArtUri.isNullOrBlank() }
            .ifEmpty { library.songs }
            .take(24)
            .ifEmpty { fallbackPreviewSongs }
    }
    var selectedIndex by remember(previewSongs) { mutableIntStateOf(0) }
    var motionEnabled by remember { mutableStateOf(true) }
    var erosionScale by remember(savedTuning) { mutableFloatStateOf(savedTuning.erosionScale) }
    var featherScale by remember(savedTuning) { mutableFloatStateOf(savedTuning.featherScale) }
    var edgeParticleDensity by remember(savedTuning) {
        mutableFloatStateOf(savedTuning.edgeParticleDensity)
    }
    var edgeParticleAlpha by remember(savedTuning) {
        mutableFloatStateOf(savedTuning.edgeParticleAlpha)
    }
    var edgeTravelScale by remember(savedTuning) { mutableFloatStateOf(savedTuning.edgeTravelScale) }
    var transitionParticleDensity by remember(savedTuning) {
        mutableFloatStateOf(savedTuning.transitionParticleDensity)
    }
    var fullCoverDensity by remember { mutableFloatStateOf(ParticleCoverThemePreset.fullCoverDensity) }
    var fullCoverBaseAlpha by remember { mutableFloatStateOf(ParticleCoverThemePreset.fullCoverBaseAlpha) }
    var fullCoverParticleAlpha by remember { mutableFloatStateOf(ParticleCoverThemePreset.fullCoverParticleAlpha) }
    var fullCoverParticleSize by remember { mutableFloatStateOf(ParticleCoverThemePreset.fullCoverParticleSize) }
    var fullCoverParticleSizeVariance by remember {
        mutableFloatStateOf(ParticleCoverThemePreset.fullCoverParticleSizeVariance)
    }
    var fullCoverGridStrength by remember { mutableFloatStateOf(ParticleCoverThemePreset.fullCoverGridStrength) }
    var fullCoverWobble by remember { mutableFloatStateOf(ParticleCoverThemePreset.fullCoverWobble) }
    var implementation by remember { mutableStateOf(ParticleCoverPreviewImplementation.NativeGl) }
    var playbackPreviewProgress by remember { mutableFloatStateOf(0f) }
    var playbackPreviewRunning by remember { mutableStateOf(false) }
    var savedNoticeVisible by remember { mutableStateOf(false) }

    val song = previewSongs[selectedIndex.coerceIn(0, previewSongs.lastIndex)]
    val tuning = ParticleCoverTuning(
        erosionScale = erosionScale,
        featherScale = featherScale,
        edgeParticleDensity = edgeParticleDensity,
        edgeParticleAlpha = edgeParticleAlpha,
        edgeTravelScale = edgeTravelScale,
        transitionParticleDensity = transitionParticleDensity,
    )
    val density = LocalDensity.current
    val coverDecodeTarget = remember(density) {
        CoverDecodeTarget.forSpecialTheme(with(density) { 360.dp.toPx() })
    }

    fun move(delta: Int) {
        selectedIndex = Math.floorMod(selectedIndex + delta, previewSongs.size)
    }

    LaunchedEffect(song.id, implementation) {
        if (implementation == ParticleCoverPreviewImplementation.PlaybackDisintegration) {
            delay(900L)
            playbackPreviewProgress = 0f
        }
    }

    LaunchedEffect(playbackPreviewRunning, implementation, song.id) {
        if (!playbackPreviewRunning ||
            implementation != ParticleCoverPreviewImplementation.PlaybackDisintegration
        ) {
            return@LaunchedEffect
        }
        while (true) {
            delay(100L)
            val durationMs = song.durationSec.coerceAtLeast(30) * 1000f
            playbackPreviewProgress = (playbackPreviewProgress + 100f / durationMs)
                .let { if (it >= 1f) 0f else it }
        }
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
            Box(
                modifier = Modifier
                    .size(HifiSize.touchTarget)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = MicaTheme.colors.textPrimary,
                )
            }
            Text(
                text = "粒子封面预览",
                style = MicaTheme.typography.display,
                color = MicaTheme.colors.textPrimary,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSectionTitle("预览")
            Spacer(Modifier.height(HifiSpacing.lg))
            BoxWithConstraints(
                modifier = Modifier
                    .padding(horizontal = HifiSpacing.lg)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .align(Alignment.CenterHorizontally),
                contentAlignment = Alignment.Center,
            ) {
                val previewSize = maxWidth
                when (implementation) {
                    ParticleCoverPreviewImplementation.WebView -> {
                        ThreeParticleCoverHost(
                            song = song,
                            coverDecodeTarget = coverDecodeTarget,
                            motionEnabled = motionEnabled,
                            coverColor = song.coverColor,
                            tuning = tuning,
                            modifier = Modifier.size(previewSize),
                        )
                    }
                    ParticleCoverPreviewImplementation.NativeGl -> {
                        ParticleCoverHost(
                            song = song,
                            coverDecodeTarget = coverDecodeTarget,
                            motionEnabled = motionEnabled,
                            coverColor = song.coverColor,
                            tuning = tuning,
                            onAspectRatioChanged = {},
                            onMotionActiveChanged = {},
                            modifier = Modifier.size(previewSize),
                        )
                    }
                    ParticleCoverPreviewImplementation.FullCoverParticles -> {
                        ParticleCoverHost(
                            song = song,
                            coverDecodeTarget = coverDecodeTarget,
                            motionEnabled = motionEnabled,
                            coverColor = song.coverColor,
                            tuning = tuning,
                            previewOptions = ParticleCoverPreviewOptions(
                                fullCoverParticles = true,
                                fullCoverDensity = fullCoverDensity,
                                fullCoverBaseAlpha = fullCoverBaseAlpha,
                                fullCoverParticleAlpha = fullCoverParticleAlpha,
                                fullCoverParticleSize = fullCoverParticleSize,
                                fullCoverParticleSizeVariance = fullCoverParticleSizeVariance,
                                fullCoverGridStrength = fullCoverGridStrength,
                                fullCoverWobble = fullCoverWobble,
                            ),
                            onAspectRatioChanged = {},
                            onMotionActiveChanged = {},
                            modifier = Modifier.size(previewSize),
                        )
                    }
                    ParticleCoverPreviewImplementation.PlaybackDisintegration -> {
                        ParticleCoverHost(
                            song = song,
                            coverDecodeTarget = coverDecodeTarget,
                            motionEnabled = motionEnabled,
                            coverColor = song.coverColor,
                            tuning = tuning,
                            playbackDisintegrationProgress = playbackPreviewProgress,
                            onAspectRatioChanged = {},
                            onMotionActiveChanged = {},
                            modifier = Modifier.size(previewSize),
                        )
                    }
                }
            }

            SettingsSectionTitle("预览操作")
            ParticleActionRow {
                ParticleTextButton(label = "上一张", onClick = { move(-1) })
                ParticleTextButton(label = "模拟切歌", emphasized = true, onClick = { move(1) })
                ParticleTextButton(label = "下一张", onClick = { move(1) })
                ParticleTextButton(
                    label = "WebView",
                    emphasized = implementation == ParticleCoverPreviewImplementation.WebView,
                    onClick = { implementation = ParticleCoverPreviewImplementation.WebView },
                )
                ParticleTextButton(
                    label = "Native GL",
                    emphasized = implementation == ParticleCoverPreviewImplementation.NativeGl,
                    onClick = { implementation = ParticleCoverPreviewImplementation.NativeGl },
                )
                ParticleTextButton(
                    label = "整图粒子",
                    emphasized = implementation == ParticleCoverPreviewImplementation.FullCoverParticles,
                    onClick = { implementation = ParticleCoverPreviewImplementation.FullCoverParticles },
                )
                ParticleTextButton(
                    label = "进度分解",
                    emphasized = implementation == ParticleCoverPreviewImplementation.PlaybackDisintegration,
                    onClick = { implementation = ParticleCoverPreviewImplementation.PlaybackDisintegration },
                )
                ParticleTextButton(
                    label = if (playbackPreviewRunning) "暂停进度" else "播放进度",
                    emphasized = playbackPreviewRunning,
                    onClick = {
                        implementation = ParticleCoverPreviewImplementation.PlaybackDisintegration
                        playbackPreviewRunning = !playbackPreviewRunning
                    },
                )
                ParticleTextButton(
                    label = "重置进度",
                    onClick = {
                        implementation = ParticleCoverPreviewImplementation.PlaybackDisintegration
                        playbackPreviewRunning = false
                        playbackPreviewProgress = 0f
                    },
                )
            }

            ParticleTuningSlider(
                label = "模拟歌曲进度",
                description = "只用于预览整首歌逐渐分解，不会保存到主题参数",
                value = playbackPreviewProgress,
                valueRange = 0f..1f,
                onValueChange = {
                    implementation = ParticleCoverPreviewImplementation.PlaybackDisintegration
                    playbackPreviewProgress = it
                },
            )

            if (implementation == ParticleCoverPreviewImplementation.FullCoverParticles) {
                SettingsSectionTitle("整图粒子实验")
                ParticleTuningSlider(
                    label = "整图粒子密度",
                    description = "控制用多少 GPU 点阵去拼出封面；当前上限约 35344 个粒子",
                    value = fullCoverDensity,
                    valueRange = 0.30f..1.0f,
                    onValueChange = { fullCoverDensity = it },
                )
                ParticleTuningSlider(
                    label = "底图透明度",
                    description = "降低后更容易判断是否真由粒子拼出封面",
                    value = fullCoverBaseAlpha,
                    valueRange = 0f..0.35f,
                    onValueChange = { fullCoverBaseAlpha = it },
                )
                ParticleTuningSlider(
                    label = "整图粒子亮度",
                    description = "只影响实验点阵，不会保存到播放页主题参数",
                    value = fullCoverParticleAlpha,
                    valueRange = 0.35f..3.0f,
                    onValueChange = { fullCoverParticleAlpha = it },
                )
                ParticleTuningSlider(
                    label = "整图粒子大小",
                    description = "放大点阵颗粒；过大时封面会更糊但更有颗粒感",
                    value = fullCoverParticleSize,
                    valueRange = 0.70f..2.40f,
                    onValueChange = { fullCoverParticleSize = it },
                )
                ParticleTuningSlider(
                    label = "粒子大小随机度",
                    description = "降到 0 时每个点大小一致；升高后保留当前这种轻微颗粒差异",
                    value = fullCoverParticleSizeVariance,
                    valueRange = 0f..1f,
                    onValueChange = { fullCoverParticleSizeVariance = it },
                )
                ParticleTuningSlider(
                    label = "网格规整度",
                    description = "提高后粒子更像一张规则网，运动也会变成坐标波纹",
                    value = fullCoverGridStrength,
                    valueRange = 0f..1f,
                    onValueChange = { fullCoverGridStrength = it },
                )
                ParticleTuningSlider(
                    label = "规律运动",
                    description = "用 shader 时间参数制造轻微波动；不会逐粒子走 CPU 更新",
                    value = fullCoverWobble,
                    valueRange = 0f..2.0f,
                    onValueChange = { fullCoverWobble = it },
                )
            }

            SettingsSectionTitle("应用参数")
            ParticleActionRow {
                ParticleTextButton(
                    label = if (motionEnabled) "关闭动画" else "开启动画",
                    onClick = { motionEnabled = !motionEnabled },
                )
                ParticleTextButton(
                    label = "重置参数",
                    onClick = {
                        val defaults = ParticleCoverTuning()
                        erosionScale = defaults.erosionScale
                        featherScale = defaults.featherScale
                        edgeParticleDensity = defaults.edgeParticleDensity
                        edgeParticleAlpha = defaults.edgeParticleAlpha
                        edgeTravelScale = defaults.edgeTravelScale
                        transitionParticleDensity = defaults.transitionParticleDensity
                        fullCoverDensity = ParticleCoverThemePreset.fullCoverDensity
                        fullCoverBaseAlpha = ParticleCoverThemePreset.fullCoverBaseAlpha
                        fullCoverParticleAlpha = ParticleCoverThemePreset.fullCoverParticleAlpha
                        fullCoverParticleSize = ParticleCoverThemePreset.fullCoverParticleSize
                        fullCoverParticleSizeVariance = ParticleCoverThemePreset.fullCoverParticleSizeVariance
                        fullCoverGridStrength = ParticleCoverThemePreset.fullCoverGridStrength
                        fullCoverWobble = ParticleCoverThemePreset.fullCoverWobble
                        savedNoticeVisible = false
                    },
                )
                ParticleTextButton(
                    label = "保存到主题",
                    emphasized = true,
                    onClick = {
                        onSaveTuning(tuning)
                        savedNoticeVisible = true
                    },
                )
            }

            if (savedNoticeVisible) {
                Text(
                    text = "已保存，播放页粒子封面会使用这组参数",
                    style = MicaTheme.typography.caption,
                    color = MicaTheme.colors.textTertiary,
                    modifier = Modifier.padding(
                        horizontal = HifiSpacing.lg,
                        vertical = HifiSpacing.sm,
                    ),
                )
            }

            SettingsSectionTitle("稳定态")
            ParticleTuningSlider(
                label = "分解程度",
                description = "控制封面边缘常驻缺损与粒子化程度",
                value = erosionScale,
                valueRange = 0.45f..1.85f,
                onValueChange = { erosionScale = it },
            )
            ParticleTuningSlider(
                label = "边缘渐变",
                description = "控制封面边缘到粒子带之间的过渡宽度",
                value = featherScale,
                valueRange = 0.55f..2.60f,
                onValueChange = { featherScale = it },
            )
            ParticleTuningSlider(
                label = "边缘粒子疏密",
                description = "只影响稳定态边缘粒子的数量",
                value = edgeParticleDensity,
                valueRange = 0.25f..1.25f,
                onValueChange = { edgeParticleDensity = it },
            )
            ParticleTuningSlider(
                label = "边缘粒子亮度",
                description = "提高粒子可见度，过高会显得像噪点层",
                value = edgeParticleAlpha,
                valueRange = 0.35f..1.55f,
                onValueChange = { edgeParticleAlpha = it },
            )
            ParticleTuningSlider(
                label = "粒子外漂",
                description = "控制稳定态边缘粒子离开封面的距离",
                value = edgeTravelScale,
                valueRange = 0.25f..2.0f,
                onValueChange = { edgeTravelScale = it },
            )

            SettingsSectionTitle("切歌")
            ParticleTuningSlider(
                label = "切歌粒子疏密",
                description = "控制切歌整图分解和重组时的粒子数",
                value = transitionParticleDensity,
                valueRange = 0.25f..1.0f,
                onValueChange = { transitionParticleDensity = it },
            )

            Text(
                text = "说明：这个页面只用于快速检查粒子封面视觉，不会改变播放队列，也不会真正播放音乐。建议先调“边缘渐变”和“分解程度”，再调粒子疏密。",
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textTertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.md),
            )
            Spacer(Modifier.height(HifiSpacing.lg + bottomContentClearance))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParticleActionRow(
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
    ) {
        content()
    }
}

@Composable
private fun ParticleTextButton(
    label: String,
    onClick: () -> Unit,
    emphasized: Boolean = false,
) {
    val color = if (emphasized) MicaTheme.colors.accent else MicaTheme.colors.textPrimary
    Text(
        text = label,
        style = MicaTheme.typography.bodySm,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = if (emphasized) 0.16f else 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = HifiSpacing.md, vertical = HifiSpacing.xs),
    )
}

@Composable
private fun ParticleTuningSlider(
    label: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MicaTheme.typography.bodyMd,
                color = MicaTheme.colors.textPrimary,
            )
            Text(
                text = "%.2f×".format(value),
                style = MicaTheme.typography.monoMd,
                color = MicaTheme.colors.textTertiary,
            )
        }
        Text(
            text = description,
            style = MicaTheme.typography.caption,
            color = MicaTheme.colors.textTertiary,
            modifier = Modifier.padding(top = HifiSpacing.xxs),
        )
        ParticleFlatSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.padding(top = HifiSpacing.xs),
        )
        HorizontalDivider(
            color = MicaTheme.colors.divider,
            modifier = Modifier.padding(top = HifiSpacing.xs),
        )
    }
}

@Composable
private fun ParticleFlatSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val range = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val trackColor = MicaTheme.colors.divider
    val fillColor = MicaTheme.colors.accent
    val thumbColor = MicaTheme.colors.accent

    var widthPx by remember { mutableFloatStateOf(0f) }

    fun xToValue(x: Float): Float {
        if (widthPx <= 0f) return value
        val t = (x / widthPx).coerceIn(0f, 1f)
        return (valueRange.start + t * range).coerceIn(valueRange.start, valueRange.endInclusive)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(valueRange) {
                detectTapGestures { offset ->
                    onValueChange(xToValue(offset.x))
                }
            }
            .pointerInput(valueRange) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onValueChange(xToValue(change.position.x))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val trackH = 2.dp.toPx()
            val cy = size.height / 2f
            val t = ((value - valueRange.start) / range).coerceIn(0f, 1f)
            val x = size.width * t

            drawRect(
                color = trackColor,
                topLeft = Offset(0f, cy - trackH / 2f),
                size = Size(size.width, trackH),
            )
            if (x > 0.5f) {
                drawRect(
                    color = fillColor,
                    topLeft = Offset(0f, cy - trackH / 2f),
                    size = Size(x, trackH),
                )
            }
            val thumbW = 12.dp.toPx()
            val thumbH = 4.dp.toPx()
            drawRect(
                color = thumbColor,
                topLeft = Offset(x - thumbW / 2f, cy - thumbH / 2f),
                size = Size(thumbW, thumbH),
            )
        }
    }
}

private val fallbackPreviewSongs = listOf(
    previewSong("particle-preview-1", "Echoes", "Particle Lab", 0xffd8d1c4.toInt()),
    previewSong("particle-preview-2", "Night Bloom", "Particle Lab", 0xff5a6f96.toInt()),
    previewSong("particle-preview-3", "Amber Dust", "Particle Lab", 0xffb97a38.toInt()),
)

private fun previewSong(
    id: String,
    title: String,
    artist: String,
    color: Int,
): Song = Song(
    id = id,
    title = title,
    artist = artist,
    album = "Particle Preview",
    albumArtist = artist,
    durationSec = 180,
    metadata = TrackMetadata.fallback(
        mimeType = "audio/mpeg",
        bitrateBpsFromStore = 320_000,
        displayName = "$title.mp3",
    ),
    albumArtUri = null,
    coverColorArgb = color,
    mediaUri = "",
)

private enum class ParticleCoverPreviewImplementation {
    WebView,
    NativeGl,
    FullCoverParticles,
    PlaybackDisintegration,
}
