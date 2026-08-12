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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.MusicLibrary
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.screens.player.PhotoStackFrame
import com.mica.music.ui.screens.player.view.PhotoStackCarouselNavigationBridge
import com.mica.music.ui.screens.player.view.PhotoStackShadowTuning
import com.mica.music.ui.screens.player.view.PhotoStackTransitionHost
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.micaAppBackground

@Composable
fun PhotoStackShadowPreviewScreen(
    library: MusicLibrary,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    bottomContentClearance: Dp = 0.dp,
) {
    val previewSongs = remember(library.songs) {
        library.songs
            .filter { !it.albumArtUri.isNullOrBlank() }
            .ifEmpty { library.songs }
            .take(16)
            .ifEmpty { fallbackShadowPreviewSongs }
    }
    var selectedIndex by remember(previewSongs) { mutableIntStateOf(0) }
    var motionEnabled by remember { mutableStateOf(true) }
    var backgroundMode by remember { mutableStateOf(ShadowPreviewBackground.Dark) }
    var shadowTuning by remember { mutableStateOf(PhotoStackShadowTuning()) }
    val navigationBridge = remember { PhotoStackCarouselNavigationBridge() }

    val seekState = remember {
        PlaybackSeekState(
            sliderValue = 64f,
            displaySec = 64,
            totalSec = 180,
            valueRange = 0f..180f,
            onValueChange = {},
            onValueChangeFinished = {},
        )
    }

    fun move(delta: Int) {
        val target = (selectedIndex + delta).coerceIn(0, previewSongs.lastIndex)
        if (target != selectedIndex) {
            navigationBridge.skipToIndex(target)
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
                    contentDescription = "Back",
                    tint = MicaTheme.colors.textPrimary,
                )
            }
            Text(
                text = "Photo Stack Shadow Preview",
                style = MicaTheme.typography.display,
                color = MicaTheme.colors.textPrimary,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSectionTitle("Preview")
            BoxWithConstraints(
                modifier = Modifier
                    .padding(horizontal = HifiSpacing.lg)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .aspectRatio(0.92f)
                    .align(Alignment.CenterHorizontally)
                    .background(backgroundMode.color),
                    contentAlignment = Alignment.Center,
            ) {
                val cardWidth = (maxWidth * 0.76f).coerceAtMost(300.dp)
                val cardHeight = cardWidth * 1.14f
                val frame = remember(cardWidth, cardHeight) {
                    PhotoStackFrame(
                        enabled = true,
                        normalLayerVisible = true,
                        immersiveProgress = 0f,
                        slotWidth = cardWidth,
                        slotHeight = cardHeight,
                        cardTopInset = 0.dp,
                        cardWidth = cardWidth,
                        cardHeight = cardHeight,
                        artworkInsetTop = cardWidth * 0.055f,
                        artworkInsetHorizontal = cardWidth * 0.038f,
                        artworkBottomBand = cardHeight - cardWidth - cardWidth * 0.055f,
                        waveformHeight = 24.dp,
                    )
                }

                PhotoStackTransitionHost(
                    queue = previewSongs,
                    currentIndex = selectedIndex.coerceIn(0, previewSongs.lastIndex),
                    frame = frame,
                    motionEnabled = motionEnabled,
                    shadowTuning = shadowTuning,
                    seekState = seekState,
                    isPlaying = false,
                    spectrumEnabled = false,
                    gesturesEnabled = true,
                    onPrevious = { move(-1) },
                    onNext = { move(1) },
                    onPlayQueueIndex = { selectedIndex = it },
                    onMotionActiveChanged = {},
                    navigationBridge = navigationBridge,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Text(
                text = previewSongs[selectedIndex.coerceIn(0, previewSongs.lastIndex)].title,
                style = MicaTheme.typography.bodyLg,
                color = MicaTheme.colors.textPrimary,
                modifier = Modifier.padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
            )

            SettingsSectionTitle("Actions")
            ShadowActionRow {
                ShadowTextButton(label = "Prev", onClick = { move(-1) })
                ShadowTextButton(label = "Next", emphasized = true, onClick = { move(1) })
                ShadowTextButton(
                    label = if (motionEnabled) "Motion On" else "Motion Off",
                    onClick = { motionEnabled = !motionEnabled },
                    emphasized = motionEnabled,
                )
                ShadowTextButton(
                    label = "Reset",
                    onClick = { shadowTuning = PhotoStackShadowTuning() },
                )
            }

            SettingsSectionTitle("Background")
            ShadowActionRow {
                ShadowPreviewBackground.entries.forEach { mode ->
                    ShadowTextButton(
                        label = mode.label,
                        emphasized = backgroundMode == mode,
                        onClick = { backgroundMode = mode },
                    )
                }
            }

            SettingsSectionTitle("Spread")
            ShadowTuningSlider(
                label = "Side spread",
                value = shadowTuning.sideSpreadDp,
                valueRange = 0f..40f,
                onValueChange = { shadowTuning = shadowTuning.copy(sideSpreadDp = it) },
            )
            ShadowTuningSlider(
                label = "Top spread",
                value = shadowTuning.topSpreadDp,
                valueRange = 0f..28f,
                onValueChange = { shadowTuning = shadowTuning.copy(topSpreadDp = it) },
            )
            ShadowTuningSlider(
                label = "Bottom spread",
                value = shadowTuning.bottomSpreadDp,
                valueRange = 0f..44f,
                onValueChange = { shadowTuning = shadowTuning.copy(bottomSpreadDp = it) },
            )
            ShadowTuningSlider(
                label = "Bottom offset",
                value = shadowTuning.bottomOffsetDp,
                valueRange = 0f..20f,
                onValueChange = { shadowTuning = shadowTuning.copy(bottomOffsetDp = it) },
            )
            ShadowTuningSlider(
                label = "Side inset",
                value = shadowTuning.sideInsetDp,
                valueRange = 0f..24f,
                onValueChange = { shadowTuning = shadowTuning.copy(sideInsetDp = it) },
            )

            SettingsSectionTitle("Shape")
            ShadowTuningSlider(
                label = "Top corner radius",
                value = shadowTuning.topCornerRadiusDp,
                valueRange = 0f..32f,
                onValueChange = { shadowTuning = shadowTuning.copy(topCornerRadiusDp = it) },
            )
            ShadowTuningSlider(
                label = "Bottom corner radius",
                value = shadowTuning.bottomCornerRadiusDp,
                valueRange = 0f..40f,
                onValueChange = { shadowTuning = shadowTuning.copy(bottomCornerRadiusDp = it) },
            )

            SettingsSectionTitle("Opacity")
            ShadowTuningSlider(
                label = "Side alpha",
                value = shadowTuning.sideAlpha,
                valueRange = 0f..0.30f,
                onValueChange = { shadowTuning = shadowTuning.copy(sideAlpha = it) },
            )
            ShadowTuningSlider(
                label = "Top alpha",
                value = shadowTuning.topAlpha,
                valueRange = 0f..0.20f,
                onValueChange = { shadowTuning = shadowTuning.copy(topAlpha = it) },
            )
            ShadowTuningSlider(
                label = "Bottom alpha",
                value = shadowTuning.bottomAlpha,
                valueRange = 0f..0.35f,
                onValueChange = { shadowTuning = shadowTuning.copy(bottomAlpha = it) },
            )
            ShadowTuningSlider(
                label = "Top corner alpha",
                value = shadowTuning.topCornerAlpha,
                valueRange = 0f..0.25f,
                onValueChange = { shadowTuning = shadowTuning.copy(topCornerAlpha = it) },
            )
            ShadowTuningSlider(
                label = "Bottom corner alpha",
                value = shadowTuning.bottomCornerAlpha,
                valueRange = 0f..0.35f,
                onValueChange = { shadowTuning = shadowTuning.copy(bottomCornerAlpha = it) },
            )

            Spacer(Modifier.height(HifiSpacing.xxl + bottomContentClearance))
        }
    }
}

private enum class ShadowPreviewBackground(
    val label: String,
    val color: Color,
) {
    Dark("Dark", Color(0xFF191715)),
    Light("Light", Color(0xFFECE3D7)),
    Cool("Cool", Color(0xFFD9DCE2)),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShadowActionRow(
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
private fun ShadowTextButton(
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
private fun ShadowTuningSlider(
    label: String,
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
                text = if (valueRange.endInclusive <= 1f) {
                    "%.2f".format(value)
                } else {
                    "%.1f".format(value)
                },
                style = MicaTheme.typography.monoMd,
                color = MicaTheme.colors.textTertiary,
            )
        }
        ShadowFlatSlider(
            value = value,
            valueRange = valueRange,
            onValueChange = onValueChange,
            modifier = Modifier.padding(top = HifiSpacing.xs),
        )
        HorizontalDivider(
            color = MicaTheme.colors.divider,
            modifier = Modifier.padding(top = HifiSpacing.xs),
        )
    }
}

@Composable
private fun ShadowFlatSlider(
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

private val fallbackShadowPreviewSongs = listOf(
    shadowPreviewSong("photo-shadow-1", "Faded Memory", 0xFFC1A07E.toInt()),
    shadowPreviewSong("photo-shadow-2", "Blue Noon", 0xFF6E86A6.toInt()),
    shadowPreviewSong("photo-shadow-3", "Silver Dust", 0xFF7E817F.toInt()),
    shadowPreviewSong("photo-shadow-4", "Soft Static", 0xFFB86B5D.toInt()),
)

private fun shadowPreviewSong(
    id: String,
    title: String,
    color: Int,
): Song = Song(
    id = id,
    title = title,
    artist = "Preview",
    album = "Photo Stack Shadow Preview",
    albumArtist = "Preview",
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
