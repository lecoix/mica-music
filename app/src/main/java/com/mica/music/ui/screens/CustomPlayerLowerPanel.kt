package com.mica.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsRenderState
import com.mica.music.data.PlaybackContentColorMode
import com.mica.music.playback.PlaybackSurfaceState
import com.mica.music.data.PlaybackTuning
import com.mica.music.data.PlayerInfoVisibility
import com.mica.music.data.HiResBadgeAppearance
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.PlayerControlButton
import com.mica.music.data.PlayerLowerComponent
import com.mica.music.data.PlayerLowerElementOffset
import com.mica.music.data.PlayerLowerLayoutConfig
import com.mica.music.data.PlayerLowerTextAlign
import com.mica.music.data.PlayerLowerTextTarget
import com.mica.music.data.Song
import com.mica.music.data.SongTitleDisplay
import com.mica.music.data.TrackSkipDirection
import com.mica.music.ui.components.DirectionalTrackWipe
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.components.PlayerPlaybackControlsSection
import com.mica.music.ui.components.PlayerProgressBarSection
import com.mica.music.ui.theme.CustomPlayerInfoRowHeightDp
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.PlayerContentColors
import com.mica.music.ui.theme.rememberLyricsContentColors
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private data class CustomLyricsVisual(
    val song: Song,
    val renderState: LyricsRenderState,
    val isPlaying: Boolean,
)

@Composable
internal fun CustomPlayerPagePanel(
    config: PlayerLowerLayoutConfig,
    coverBaseHeightDp: Float,
    coverContent: @Composable (visualScale: Float) -> Unit,
    surfaceState: PlaybackSurfaceState,
    activeSong: Song,
    lyricsRenderState: LyricsRenderState,
    autoContentColors: PlayerContentColors,
    colors: PlayerContentColors,
    hifiBadgeColors: PlayerContentColors,
    playerPageTextColorMode: PlaybackContentColorMode,
    lowerBackground: PlayerLowerBackgroundMode,
    seekState: PlaybackSeekState,
    lyricsTextColorMode: PlaybackContentColorMode,
    bilingualDisplayMode: LyricsBilingualDisplayMode,
    stripSongTitleParentheses: Boolean,
    playerInfoVisibility: PlayerInfoVisibility,
    hiResBadgeAppearance: HiResBadgeAppearance,
    playbackTuning: PlaybackTuning,
    spectrumEnabled: Boolean,
    trackSkipDirection: TrackSkipDirection?,
    trackWipeMotionEnabled: Boolean,
    onCyclePlaybackQueueMode: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    isEditing: Boolean = false,
    selectedComponent: PlayerLowerComponent = PlayerLowerComponent.COVER,
    onEnterEditMode: () -> Unit = {},
    onSelectComponent: (PlayerLowerComponent) -> Unit = {},
    onEditConfigChange: ((PlayerLowerLayoutConfig) -> PlayerLowerLayoutConfig) -> Unit = {},
    onSaveEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onResetEdit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val normalized = config.normalized()
    val visible = normalized.order.filter(normalized::isVisible)
    val hasLyrics = PlayerLowerComponent.LYRICS in visible
    val lyricsColors = rememberLyricsContentColors(autoContentColors, lyricsTextColorMode)
    val infoColors = when {
        playerPageTextColorMode != PlaybackContentColorMode.AUTO -> colors
        lowerBackground.usesBlurredArtwork -> hifiBadgeColors
        else -> colors
    }

    val currentOnEnterEditMode = rememberUpdatedState(onEnterEditMode)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .then(
                if (!isEditing) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Final,
                            )
                            if (down.isConsumed) return@awaitEachGesture
                            val endedBeforeLongPress = withTimeoutOrNull(
                                viewConfiguration.longPressTimeoutMillis,
                            ) {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Final)
                                    if (event.changes.any { it.isConsumed || !it.pressed }) return@withTimeoutOrNull true
                                }
                                @Suppress("UNREACHABLE_CODE")
                                false
                            }
                            if (endedBeforeLongPress == null) currentOnEnterEditMode.value()
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        val density = LocalDensity.current
        val panelWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val panelHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val panelHeightDp = maxHeight.value.coerceAtLeast(1f)
        val metrics = customPlayerLayoutMetrics(
            panelHeightDp = maxHeight.value,
            coverBaseHeightDp = coverBaseHeightDp,
            config = normalized,
            visible = visible,
        )
        val fitScale = metrics.fitScale
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                visible.forEach { component ->
                    val scale = normalized.scalePercentOf(component) / 100f * fitScale
                    val baselineCenterDp = customPlayerFreeformBaselineCenterDp(
                        component = component,
                        visible = visible,
                        config = normalized,
                        coverBaseHeightDp = coverBaseHeightDp,
                        fitScale = fitScale,
                    )
                    val minYPermille = (-baselineCenterDp / panelHeightDp * 1_000f).toInt()
                    val maxYPermille = (
                        (panelHeightDp - baselineCenterDp) / panelHeightDp * 1_000f
                        ).toInt()
                    val itemHeightDp = customPlayerBaseHeightDp(
                        component = component,
                        lyricsLineCount = normalized.lyricsLineCount,
                        coverBaseHeightDp = coverBaseHeightDp,
                    ) * scale
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(itemHeightDp.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (component) {
                            PlayerLowerComponent.COVER -> coverContent(scale)

                            PlayerLowerComponent.INFO -> DirectionalTrackWipe(
                                targetState = activeSong,
                                contentKey = Song::id,
                                direction = trackSkipDirection,
                                motionEnabled = trackWipeMotionEnabled,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    },
                            ) { visualSong ->
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    if (playerInfoVisibility.hasAnyEnabledSegment()) {
                                        HiFiBadgeSection(
                                            song = visualSong,
                                            colors = infoColors,
                                            playerInfoVisibility = playerInfoVisibility,
                                            playbackTuning = playbackTuning,
                                            hiResBadgeAppearance = hiResBadgeAppearance,
                                        )
                                    }
                                }
                            }

                            PlayerLowerComponent.TITLE -> DirectionalTrackWipe(
                                targetState = activeSong,
                                contentKey = Song::id,
                                direction = trackSkipDirection,
                                motionEnabled = trackWipeMotionEnabled,
                                modifier = Modifier.fillMaxWidth(),
                            ) { visualSong ->
                                SongTitleSection(
                                    title = SongTitleDisplay.displayTitle(
                                        visualSong.title,
                                        stripSongTitleParentheses,
                                    ),
                                    artist = visualSong.artist,
                                    album = visualSong.album,
                                    isBuffering = surfaceState.isBuffering,
                                    playbackError = surfaceState.playbackError,
                                    colors = colors,
                                    immersiveProgress = 0f,
                                    contentScale = scale,
                                    titleTextAlign = normalized
                                        .textAlignOf(PlayerLowerTextTarget.TITLE)
                                        .toTextAlign(),
                                    subtitleTextAlign = normalized
                                        .textAlignOf(PlayerLowerTextTarget.SUBTITLE)
                                        .toTextAlign(),
                                )
                            }

                            PlayerLowerComponent.LYRICS -> DirectionalTrackWipe(
                                targetState = CustomLyricsVisual(
                                    activeSong,
                                    lyricsRenderState,
                                    surfaceState.isPlaying,
                                ),
                                contentKey = { it.song.id },
                                direction = trackSkipDirection,
                                motionEnabled = trackWipeMotionEnabled,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .fillMaxWidth(),
                            ) { visual ->
                                CustomLyricsBlock(
                                    renderState = visual.renderState,
                                    isPlaying = visual.isPlaying,
                                    colors = lyricsColors,
                                    bilingualDisplayMode = bilingualDisplayMode,
                                    contentScale = scale,
                                    lineSlots = normalized.lyricsLineCount,
                                    textAlign = normalized
                                        .textAlignOf(PlayerLowerTextTarget.LYRICS)
                                        .toTextAlign(),
                                    onOpenLyrics = onOpenLyrics,
                                )
                            }

                            PlayerLowerComponent.PROGRESS -> PlayerProgressBarSection(
                                seekState = seekState,
                                colors = colors,
                                spectrumEnabled = spectrumEnabled,
                                spectrumPlaying = surfaceState.isPlaying,
                                spectrumHeight = 56.dp * scale,
                                visualScale = scale,
                                modifier = Modifier.padding(horizontal = HifiSpacing.lg),
                            )

                            PlayerLowerComponent.CONTROLS -> PlayerPlaybackControlsSection(
                                surfaceState = surfaceState,
                                colors = colors,
                                onCyclePlaybackQueueMode = onCyclePlaybackQueueMode,
                                onPrevious = onPrevious,
                                onTogglePlay = onTogglePlay,
                                onNext = onNext,
                                onOpenQueue = onOpenQueue,
                                visualScale = scale,
                                hiddenButtons = normalized.hiddenControls,
                                modifier = Modifier.padding(horizontal = HifiSpacing.lg),
                            )
                        }
                        if (isEditing) {
                            CustomPlayerElementEditTarget(
                                component = component,
                                selected = selectedComponent == component,
                                onSelect = { onSelectComponent(component) },
                                onTransform = { pan, zoom ->
                                    onEditConfigChange { currentConfig ->
                                        updateCustomPlayerElementTransform(
                                            config = currentConfig,
                                            component = component,
                                            pan = pan,
                                            zoom = zoom,
                                            panelWidthPx = panelWidthPx,
                                            panelHeightPx = panelHeightPx,
                                            minYPermille = minYPermille,
                                            maxYPermille = maxYPermille,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
            val placeables = measurables.map { it.measure(childConstraints) }
            val widthPx = constraints.maxWidth
            val heightPx = constraints.maxHeight
            val spacingPx = with(density) { (normalized.spacingDp.dp * fitScale).roundToPx() }
            val topPaddingPx = with(density) { (normalized.topPaddingDp.dp * fitScale).roundToPx() }
            val bottomPaddingPx = with(density) { (normalized.bottomPaddingDp.dp * fitScale).roundToPx() }
            val legacyContentHeight = placeables.sumOf { it.height } +
                spacingPx * (placeables.size - 1).coerceAtLeast(0)
            val legacyAvailableHeight = (heightPx - topPaddingPx - bottomPaddingPx).coerceAtLeast(0)
            val legacyStartY = topPaddingPx + if (
                !hasLyrics && PlayerLowerComponent.COVER !in visible
            ) {
                ((legacyAvailableHeight - legacyContentHeight) / 2).coerceAtLeast(0)
            } else {
                0
            }

            layout(widthPx, heightPx) {
                var legacyY = legacyStartY
                visible.zip(placeables).forEach { (component, placeable) ->
                    if (normalized.freeformEnabled) {
                        val baselineCenterPx = with(density) {
                            customPlayerFreeformBaselineCenterDp(
                                component = component,
                                visible = visible,
                                config = normalized,
                                coverBaseHeightDp = coverBaseHeightDp,
                                fitScale = fitScale,
                            ).dp.toPx()
                        }
                        val offset = effectiveCustomPlayerOffset(normalized.offsetOf(component))
                        val x = offset.xPermille / 1_000f * widthPx
                        val y = baselineCenterPx - placeable.height / 2f +
                            offset.yPermille / 1_000f * heightPx
                        placeable.placeRelative(x.roundToInt(), y.roundToInt())
                    } else {
                        placeable.placeRelative(0, legacyY)
                        legacyY += placeable.height + spacingPx
                    }
                }
            }
        }
        if (isEditing) {
            CustomPlayerEditChrome(
                config = normalized,
                selectedComponent = selectedComponent,
                onSelectComponent = onSelectComponent,
                onVisibilityChange = { component, visible ->
                    onEditConfigChange { current -> current.withVisibility(component, visible) }
                },
                onConfigChange = onEditConfigChange,
                onSave = onSaveEdit,
                onCancel = onCancelEdit,
                onReset = onResetEdit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CustomPlayerElementEditTarget(
    component: PlayerLowerComponent,
    selected: Boolean,
    onSelect: () -> Unit,
    onTransform: (Offset, Float) -> Unit,
) {
    val accent = MicaTheme.colors.accent
    val currentOnSelect = rememberUpdatedState(onSelect)
    val currentOnTransform = rememberUpdatedState(onTransform)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (selected) {
                    Modifier
                        .background(accent.copy(alpha = 0.08f))
                        .border(1.dp, accent)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onSelect)
            .pointerInput(component) {
                detectTransformGestures { _, pan, zoom, _ ->
                    currentOnSelect.value()
                    currentOnTransform.value(pan, zoom)
                }
            },
    ) {
        if (selected) {
            Text(
                text = component.settingsLabel,
                style = MicaTheme.typography.monoSm,
                color = accent,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(MicaTheme.colors.surfaceCard.copy(alpha = 0.92f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun CustomPlayerEditChrome(
    config: PlayerLowerLayoutConfig,
    selectedComponent: PlayerLowerComponent,
    onSelectComponent: (PlayerLowerComponent) -> Unit,
    onVisibilityChange: (PlayerLowerComponent, Boolean) -> Unit,
    onConfigChange: ((PlayerLowerLayoutConfig) -> PlayerLowerLayoutConfig) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MicaTheme.colors
    Box(modifier) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .background(colors.surfaceCard.copy(alpha = 0.96f))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) {
                Icon(Icons.Outlined.Close, contentDescription = null)
                Text("取消")
            }
            TextButton(onClick = onReset) {
                Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                Text("恢复")
            }
            TextButton(onClick = onSave) {
                Icon(Icons.Outlined.Check, contentDescription = null)
                Text("保存")
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .background(colors.surfaceCard.copy(alpha = 0.96f))
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "拖动定位 · 双指缩放 · 靠近原位中心会吸附",
                style = MicaTheme.typography.caption,
                color = colors.textSecondary,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerLowerComponent.entries.forEach { component ->
                    val visible = config.isVisible(component)
                    TextButton(
                        onClick = {
                            onSelectComponent(component)
                            if (!visible) onVisibilityChange(component, true)
                        },
                    ) {
                        Text(
                            component.settingsLabel,
                            color = when {
                                component == selectedComponent -> colors.accent
                                visible -> colors.textPrimary
                                else -> colors.textTertiary
                            },
                        )
                    }
                }
                IconButton(
                    onClick = {
                        onVisibilityChange(
                            selectedComponent,
                            !config.isVisible(selectedComponent),
                        )
                    },
                ) {
                    val visible = config.isVisible(selectedComponent)
                    Icon(
                        imageVector = if (visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                        contentDescription = if (visible) "隐藏所选元素" else "显示所选元素",
                        tint = colors.accent,
                    )
                }
            }
            when (selectedComponent) {
                PlayerLowerComponent.TITLE -> {
                    TextAlignEditRow(PlayerLowerTextTarget.TITLE, config, onConfigChange)
                    TextAlignEditRow(PlayerLowerTextTarget.SUBTITLE, config, onConfigChange)
                }

                PlayerLowerComponent.LYRICS ->
                    TextAlignEditRow(PlayerLowerTextTarget.LYRICS, config, onConfigChange)

                PlayerLowerComponent.CONTROLS -> ControlButtonEditRow(config, onConfigChange)

                PlayerLowerComponent.COVER,
                PlayerLowerComponent.INFO,
                PlayerLowerComponent.PROGRESS,
                -> Unit
            }
        }

        val selectedOffset = effectiveCustomPlayerOffset(config.offsetOf(selectedComponent))
        if (selectedOffset.xPermille == 0) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(colors.accent.copy(alpha = 0.55f)),
            )
        }
        if (selectedOffset.yPermille == 0) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.accent.copy(alpha = 0.55f)),
            )
        }
    }
}

@Composable
private fun TextAlignEditRow(
    target: PlayerLowerTextTarget,
    config: PlayerLowerLayoutConfig,
    onConfigChange: ((PlayerLowerLayoutConfig) -> PlayerLowerLayoutConfig) -> Unit,
) {
    val colors = MicaTheme.colors
    val current = config.textAlignOf(target)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = target.settingsLabel,
            style = MicaTheme.typography.caption,
            color = colors.textSecondary,
        )
        PlayerLowerTextAlign.entries.forEach { align ->
            TextButton(onClick = { onConfigChange { it.withTextAlign(target, align) } }) {
                Text(
                    text = align.settingsLabel,
                    color = if (align == current) colors.accent else colors.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun ControlButtonEditRow(
    config: PlayerLowerLayoutConfig,
    onConfigChange: ((PlayerLowerLayoutConfig) -> PlayerLowerLayoutConfig) -> Unit,
) {
    val colors = MicaTheme.colors
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerControlButton.entries.forEach { button ->
            val visible = config.isControlVisible(button)
            TextButton(
                onClick = { onConfigChange { it.withControlVisibility(button, !visible) } },
            ) {
                Icon(
                    imageVector = if (visible) {
                        Icons.Outlined.Visibility
                    } else {
                        Icons.Outlined.VisibilityOff
                    },
                    contentDescription = null,
                    tint = if (visible) colors.accent else colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = button.settingsLabel,
                    color = if (visible) colors.textPrimary else colors.textTertiary,
                )
            }
        }
    }
}

internal fun updateCustomPlayerElementTransform(
    config: PlayerLowerLayoutConfig,
    component: PlayerLowerComponent,
    pan: Offset,
    zoom: Float,
    panelWidthPx: Float,
    panelHeightPx: Float,
    minYPermille: Int = PlayerLowerElementOffset.MIN_OFFSET_PERMILLE,
    maxYPermille: Int = PlayerLowerElementOffset.MAX_OFFSET_PERMILLE,
): PlayerLowerLayoutConfig {
    val currentOffset = config.offsetOf(component)
    val x = (
        currentOffset.xPermille +
            (pan.x / panelWidthPx.coerceAtLeast(1f) * 1_000f).toInt()
        ).coerceIn(-500, 500)
    val y = (
        currentOffset.yPermille +
            (pan.y / panelHeightPx.coerceAtLeast(1f) * 1_000f).toInt()
        ).coerceIn(minYPermille, maxYPermille)
    val currentScale = config.scalePercentOf(component)
    val scaled = kotlin.math.round(currentScale * zoom).toInt().coerceIn(
        PlayerLowerLayoutConfig.MIN_SCALE_PERCENT,
        PlayerLowerLayoutConfig.MAX_SCALE_PERCENT,
    )
    return config
        .withElementOffset(
            component,
            PlayerLowerElementOffset(
                xPermille = x,
                yPermille = y,
            ),
        )
        .withScalePercent(component, scaled)
        .normalized()
}

internal fun effectiveCustomPlayerOffset(offset: PlayerLowerElementOffset): PlayerLowerElementOffset =
    PlayerLowerElementOffset(
        xPermille = snapCustomPlayerAxis(offset.xPermille),
        yPermille = snapCustomPlayerAxis(offset.yPermille),
    )

internal fun snapCustomPlayerLayoutOffsets(config: PlayerLowerLayoutConfig): PlayerLowerLayoutConfig =
    config.copy(
        elementOffsets = config.elementOffsets.mapValues { (_, offset) ->
            effectiveCustomPlayerOffset(offset)
        },
    ).normalized()

internal fun snapCustomPlayerAxis(value: Int): Int =
    if (kotlin.math.abs(value) <= CustomPlayerSnapThresholdPermille) 0 else value

private const val CustomPlayerSnapThresholdPermille = 5

internal data class CustomPlayerLayoutMetrics(
    val fitScale: Float,
    val coverVisualScale: Float,
    val coverTopDp: Float?,
)

internal fun customPlayerLayoutMetrics(
    panelHeightDp: Float,
    coverBaseHeightDp: Float,
    config: PlayerLowerLayoutConfig,
    visible: List<PlayerLowerComponent> = config.order.filter(config::isVisible),
): CustomPlayerLayoutMetrics {
    if (panelHeightDp <= 0f || visible.isEmpty()) {
        return CustomPlayerLayoutMetrics(fitScale = 1f, coverVisualScale = 1f, coverTopDp = null)
    }
    val normalized = config.normalized()
    val componentsHeight = visible.sumOf { component ->
        val componentScale = if (normalized.freeformEnabled) {
            1.0
        } else {
            normalized.scalePercentOf(component) / 100.0
        }
        customPlayerBaseHeightDp(component, normalized.lyricsLineCount, coverBaseHeightDp).toDouble() *
            componentScale
    }.toFloat()
    val gapsHeight = normalized.spacingDp * (visible.size - 1).coerceAtLeast(0)
    val desiredHeight = componentsHeight + gapsHeight + normalized.topPaddingDp + normalized.bottomPaddingDp
    val fitScale = if (desiredHeight <= panelHeightDp) {
        1f
    } else {
        (panelHeightDp / desiredHeight).coerceIn(0f, 1f)
    }
    var topDp = normalized.topPaddingDp * fitScale
    var coverTopDp: Float? = null
    visible.forEachIndexed { index, component ->
        val baseHeight = customPlayerBaseHeightDp(
            component,
            normalized.lyricsLineCount,
            coverBaseHeightDp,
        )
        val componentScale = normalized.scalePercentOf(component) / 100f
        if (component == PlayerLowerComponent.COVER) {
            coverTopDp = if (normalized.freeformEnabled) {
                topDp + baseHeight * (1f - componentScale) * fitScale / 2f +
                    effectiveCustomPlayerOffset(normalized.offsetOf(component)).yPermille /
                        1_000f * panelHeightDp
            } else {
                topDp
            }
        }
        topDp += baseHeight *
            (if (normalized.freeformEnabled) 1f else componentScale) * fitScale
        if (index < visible.lastIndex) topDp += normalized.spacingDp * fitScale
    }
    return CustomPlayerLayoutMetrics(
        fitScale = fitScale,
        coverVisualScale = normalized.scalePercentOf(PlayerLowerComponent.COVER) / 100f * fitScale,
        coverTopDp = coverTopDp,
    )
}

internal fun customPlayerFreeformFlowCompensationDp(
    component: PlayerLowerComponent,
    visible: List<PlayerLowerComponent>,
    config: PlayerLowerLayoutConfig,
    coverBaseHeightDp: Float,
    fitScale: Float,
): Float {
    var baseTop = config.topPaddingDp * fitScale
    var flowTop = baseTop
    visible.forEachIndexed { index, current ->
        val baseHeight = customPlayerBaseHeightDp(
            current,
            config.lyricsLineCount,
            coverBaseHeightDp,
        ) * fitScale
        val scaledHeight = baseHeight * config.scalePercentOf(current) / 100f
        if (current == component) {
            return baseTop + (baseHeight - scaledHeight) / 2f - flowTop
        }
        baseTop += baseHeight
        flowTop += scaledHeight
        if (index < visible.lastIndex) {
            val gap = config.spacingDp * fitScale
            baseTop += gap
            flowTop += gap
        }
    }
    return 0f
}

internal fun customPlayerFreeformBaselineCenterDp(
    component: PlayerLowerComponent,
    visible: List<PlayerLowerComponent>,
    config: PlayerLowerLayoutConfig,
    coverBaseHeightDp: Float,
    fitScale: Float,
): Float {
    var top = config.topPaddingDp * fitScale
    visible.forEachIndexed { index, current ->
        val baseHeight = customPlayerBaseHeightDp(
            current,
            config.lyricsLineCount,
            coverBaseHeightDp,
        ) * fitScale
        if (current == component) return top + baseHeight / 2f
        top += baseHeight
        if (index < visible.lastIndex) top += config.spacingDp * fitScale
    }
    return 0f
}

internal fun customPlayerBaseHeightDp(
    component: PlayerLowerComponent,
    lyricsLineCount: Int = PlayerLowerLayoutConfig.DEFAULT_LYRICS_LINE_COUNT,
    coverBaseHeightDp: Float = DefaultCustomCoverBaseHeightDp,
): Float = when (component) {
    PlayerLowerComponent.COVER -> coverBaseHeightDp.coerceAtLeast(0f)
    PlayerLowerComponent.INFO -> CustomPlayerInfoRowHeightDp
    PlayerLowerComponent.TITLE -> 72f
    PlayerLowerComponent.LYRICS -> if (
        lyricsLineCount == PlayerLowerLayoutConfig.SINGLE_LYRICS_LINE_COUNT
    ) 48f else 112f
    PlayerLowerComponent.PROGRESS -> 64f
    PlayerLowerComponent.CONTROLS -> 80f
}

private const val DefaultCustomCoverBaseHeightDp = 360f

@Composable
private fun CustomLyricsBlock(
    renderState: LyricsRenderState,
    isPlaying: Boolean,
    colors: PlayerContentColors,
    bilingualDisplayMode: LyricsBilingualDisplayMode,
    contentScale: Float,
    lineSlots: Int,
    textAlign: TextAlign,
    onOpenLyrics: () -> Unit,
) {
    LyricsSection(
        renderState = renderState,
        isPlaying = isPlaying,
        colors = colors,
        lineSlots = lineSlots,
        onClick = onOpenLyrics,
        bilingualDisplayMode = bilingualDisplayMode,
        contentScale = contentScale,
        textAlign = textAlign,
        modifier = Modifier
            .fillMaxSize()
            .fillMaxWidth(),
    )
}

internal fun PlayerLowerTextAlign.toTextAlign(): TextAlign = when (this) {
    PlayerLowerTextAlign.START -> TextAlign.Start
    PlayerLowerTextAlign.CENTER -> TextAlign.Center
    PlayerLowerTextAlign.END -> TextAlign.End
}
