package com.mica.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mica.music.data.PlayerLowerComponent
import com.mica.music.data.PlayerLowerLayoutConfig
import com.mica.music.ui.components.AccentTextChoice
import com.mica.music.ui.components.PlaybackTuningRuler
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.TextToggle
import com.mica.music.ui.screens.customPlayerBaseHeightDp
import com.mica.music.ui.screens.customPlayerLayoutMetrics
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import java.util.Locale
import kotlin.math.roundToInt
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val ComponentEditorListHeight = 520.dp

internal fun moveCustomPlayerComponent(
    items: MutableList<PlayerLowerComponent>,
    fromIndex: Int,
    toIndex: Int,
): Boolean {
    if (fromIndex !in items.indices || toIndex !in items.indices || fromIndex == toIndex) return false
    val moved = items.removeAt(fromIndex)
    items.add(toIndex, moved)
    return true
}

@Composable
internal fun CustomPlayerLayoutEditor(
    config: PlayerLowerLayoutConfig,
    onChange: (PlayerLowerLayoutConfig) -> Unit,
) {
    val normalized = config.normalized()
    SettingsSectionTitle("自定义布局预览")
    CustomPlayerLayoutPreview(
        config = normalized,
        onTopPaddingChange = { onChange(normalized.copy(topPaddingDp = it).normalized()) },
        onBottomPaddingChange = { onChange(normalized.copy(bottomPaddingDp = it).normalized()) },
    )

    SettingsSectionTitle("边界与间距")
    BoundaryPaddingControl(
        title = "顶部留白",
        value = normalized.topPaddingDp,
        onValueChange = { onChange(normalized.copy(topPaddingDp = it).normalized()) },
    )
    BoundaryPaddingControl(
        title = "底部留白",
        value = normalized.bottomPaddingDp,
        onValueChange = { onChange(normalized.copy(bottomPaddingDp = it).normalized()) },
    )
    SettingsRuler(
        title = "组件间距",
        valueLabel = "${normalized.spacingDp}dp",
        value = normalized.spacingDp.toFloat(),
        valueRange = PlayerLowerLayoutConfig.MIN_SPACING_DP.toFloat()..PlayerLowerLayoutConfig.MAX_SPACING_DP.toFloat(),
        step = 1f,
        majorStep = 6f,
        tickLabel = { it.roundToInt().toString() },
        onValueChange = { onChange(normalized.copy(spacingDp = it.roundToInt()).normalized()) },
    )

    SettingsSectionTitle("组件顺序、大小与显示")
    Text(
        text = "长按每项右侧的拖动手柄调整顺序",
        style = MicaTheme.typography.caption,
        color = MicaTheme.colors.textTertiary,
        modifier = Modifier.padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.xs),
    )
    val haptic = LocalHapticFeedback.current
    val componentItems = remember { mutableStateListOf<PlayerLowerComponent>() }
    val currentConfig by rememberUpdatedState(normalized)
    val currentOnChange by rememberUpdatedState(onChange)
    LaunchedEffect(normalized.order) {
        if (componentItems.toList() != normalized.order) {
            componentItems.clear()
            componentItems.addAll(normalized.order)
        }
    }
    val componentListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(componentListState) { from, to ->
        if (moveCustomPlayerComponent(componentItems, from.index, to.index)) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    LazyColumn(
        state = componentListState,
        modifier = Modifier
            .fillMaxWidth()
            .height(ComponentEditorListHeight),
    ) {
        items(componentItems, key = PlayerLowerComponent::storageValue) { component ->
            ReorderableItem(reorderState, key = component.storageValue) { isDragging ->
            CustomPlayerComponentEditorRow(
                component = component,
                scalePercent = normalized.scalePercentOf(component),
                lyricsLineCount = normalized.lyricsLineCount,
                visible = normalized.isVisible(component),
                isDragging = isDragging,
                dragModifier = Modifier.draggableHandle(
                    onDragStopped = {
                        val finalOrder = componentItems.toList()
                        if (finalOrder != currentConfig.order) {
                            currentOnChange(currentConfig.copy(order = finalOrder).normalized())
                        }
                    },
                ),
                onVisibleChange = { visible ->
                    onChange(normalized.withVisibility(component, visible))
                },
                onScaleChange = { percent ->
                    onChange(normalized.withScalePercent(component, percent))
                },
                onLyricsLineCountChange = { lineCount ->
                    onChange(normalized.copy(lyricsLineCount = lineCount).normalized())
                },
            )
            }
        }
    }

    SettingsActionRow(
        title = "恢复标准布局",
        subtitle = "恢复默认顺序、大小、间距、边界留白和显示状态",
        onClick = { onChange(PlayerLowerLayoutConfig.Default) },
    )
}

@Composable
private fun CustomPlayerLayoutPreview(
    config: PlayerLowerLayoutConfig,
    onTopPaddingChange: (Int) -> Unit,
    onBottomPaddingChange: (Int) -> Unit,
) {
    val visible = config.order.filter(config::isVisible)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg)
            .height(360.dp)
            .background(MicaTheme.colors.surfaceCard.copy(alpha = 0.55f))
            .padding(HifiSpacing.md)
            .clipToBounds(),
    ) {
        val coverBaseHeightDp = maxWidth.value
        val metrics = customPlayerLayoutMetrics(
            panelHeightDp = maxHeight.value,
            coverBaseHeightDp = coverBaseHeightDp,
            config = config,
            visible = visible,
        )
        val fit = metrics.fitScale
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "播放页组件已全部隐藏",
                    style = MicaTheme.typography.caption,
                    color = MicaTheme.colors.textTertiary,
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(config.topPaddingDp.dp * fit))
                visible.forEachIndexed { index, component ->
                    val scale = config.scalePercentOf(component) / 100f * fit
                    PreviewPlayerComponent(
                        component = component,
                        visualScale = scale,
                        heightDp = customPlayerBaseHeightDp(
                            component,
                            config.lyricsLineCount,
                            coverBaseHeightDp,
                        ) * scale,
                        lyricsLineCount = config.lyricsLineCount,
                    )
                    if (index < visible.lastIndex) {
                        Spacer(Modifier.height(config.spacingDp.dp * fit))
                    }
                }
                Spacer(Modifier.height(config.bottomPaddingDp.dp * fit))
            }
        }
        BoundaryDragHandle(
            label = "顶部",
            value = config.topPaddingDp,
            dragDirection = 1f,
            previewDpPerValueDp = fit,
            lineAtTop = true,
            onValueChange = onTopPaddingChange,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(y = (config.topPaddingDp * fit).dp),
        )
        BoundaryDragHandle(
            label = "底部",
            value = config.bottomPaddingDp,
            dragDirection = -1f,
            previewDpPerValueDp = fit,
            lineAtTop = false,
            onValueChange = onBottomPaddingChange,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(y = (-config.bottomPaddingDp * fit).dp),
        )
    }
}

@Composable
private fun BoundaryDragHandle(
    label: String,
    value: Int,
    dragDirection: Float,
    previewDpPerValueDp: Float,
    lineAtTop: Boolean,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val currentValue by rememberUpdatedState(value)
    val currentScale by rememberUpdatedState(previewDpPerValueDp)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val lineAlignment = if (lineAtTop) Alignment.TopCenter else Alignment.BottomCenter
    val labelAlignment = if (lineAtTop) Alignment.TopEnd else Alignment.BottomEnd
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HifiSize.touchTarget)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        currentOnValueChange(PlayerLowerLayoutConfig.DEFAULT_BOUNDARY_PADDING_DP)
                    },
                )
            }
            .pointerInput(Unit) {
                var pendingValue = currentValue.toFloat()
                detectDragGestures(
                    onDragStart = { pendingValue = currentValue.toFloat() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val pixelsPerValueDp = density.density * currentScale.coerceAtLeast(0.05f)
                        pendingValue += dragDirection * dragAmount.y / pixelsPerValueDp
                        currentOnValueChange(snapBoundaryPadding(pendingValue))
                    },
                )
            },
    ) {
        HorizontalDivider(
            modifier = Modifier.align(lineAlignment),
            thickness = 1.dp,
            color = MicaTheme.colors.accent.copy(alpha = 0.8f),
        )
        Text(
            text = "$label ${value}dp",
            style = MicaTheme.typography.monoSm,
            color = MicaTheme.colors.accent,
            modifier = Modifier
                .align(labelAlignment)
                .background(MicaTheme.colors.surfaceCard.copy(alpha = 0.92f))
                .padding(horizontal = HifiSpacing.xs),
        )
    }
}

internal fun snapBoundaryPadding(value: Float): Int =
    (value / 2f).roundToInt().times(2).coerceIn(
        PlayerLowerLayoutConfig.MIN_BOUNDARY_PADDING_DP,
        PlayerLowerLayoutConfig.MAX_BOUNDARY_PADDING_DP,
    )

@Composable
private fun PreviewPlayerComponent(
    component: PlayerLowerComponent,
    visualScale: Float,
    heightDp: Float,
    lyricsLineCount: Int,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp.coerceAtLeast(4.dp))
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val contentScale = if (component == PlayerLowerComponent.COVER) 1f else visualScale
                    scaleX = contentScale.coerceAtMost(1.5f)
                    scaleY = contentScale.coerceAtMost(1.5f)
                },
            contentAlignment = Alignment.Center,
        ) {
            when (component) {
                PlayerLowerComponent.COVER -> Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                        .background(MicaTheme.colors.textTertiary.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("封面", style = MicaTheme.typography.caption, color = MicaTheme.colors.textTertiary)
                }

                PlayerLowerComponent.INFO -> Text(
                    "FLAC  ·  24bit/96kHz  ·  03:48",
                    style = MicaTheme.typography.monoSm,
                    color = MicaTheme.colors.textTertiary,
                )

                PlayerLowerComponent.TITLE -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("示例歌曲", style = MicaTheme.typography.titleLg, color = MicaTheme.colors.textPrimary)
                    Text(
                        "示例歌手 · 示例专辑",
                        style = MicaTheme.typography.caption,
                        color = MicaTheme.colors.textSecondary,
                    )
                }

                PlayerLowerComponent.LYRICS -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (lyricsLineCount >= PlayerLowerLayoutConfig.THREE_LYRICS_LINE_COUNT) {
                        Text("上一句歌词", style = MicaTheme.typography.caption, color = MicaTheme.colors.textTertiary)
                    }
                    Text("正在播放的歌词", style = MicaTheme.typography.bodyLg, color = MicaTheme.colors.textPrimary)
                    if (lyricsLineCount >= PlayerLowerLayoutConfig.THREE_LYRICS_LINE_COUNT) {
                        Text("下一句歌词", style = MicaTheme.typography.caption, color = MicaTheme.colors.textTertiary)
                    }
                }

                PlayerLowerComponent.PROGRESS -> Column(Modifier.padding(horizontal = HifiSpacing.lg)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(MicaTheme.colors.textSecondary),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text("1:24", style = MicaTheme.typography.monoSm, color = MicaTheme.colors.textSecondary)
                        Text(
                            "3:48",
                            style = MicaTheme.typography.monoSm,
                            color = MicaTheme.colors.textSecondary,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                PlayerLowerComponent.CONTROLS -> Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val tint = MicaTheme.colors.textPrimary
                    Icon(Icons.Outlined.Repeat, null, tint = tint)
                    Icon(Icons.Default.SkipPrevious, null, tint = tint)
                    Icon(Icons.Default.PlayArrow, null, tint = tint, modifier = Modifier.size(HifiSize.iconXl))
                    Icon(Icons.Default.SkipNext, null, tint = tint)
                    Icon(Icons.AutoMirrored.Outlined.QueueMusic, null, tint = tint)
                }
            }
        }
    }
}

@Composable
private fun CustomPlayerComponentEditorRow(
    component: PlayerLowerComponent,
    scalePercent: Int,
    lyricsLineCount: Int,
    visible: Boolean,
    isDragging: Boolean,
    dragModifier: Modifier,
    onVisibleChange: (Boolean) -> Unit,
    onScaleChange: (Int) -> Unit,
    onLyricsLineCountChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = component.settingsLabel,
                style = MicaTheme.typography.bodyLg,
                color = if (visible) MicaTheme.colors.textPrimary else MicaTheme.colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.DragHandle,
                contentDescription = "长按拖动排序",
                tint = if (isDragging) MicaTheme.colors.accent else MicaTheme.colors.textTertiary,
                modifier = dragModifier
                    .size(HifiSize.touchTarget)
                    .padding(HifiSpacing.md),
            )
            TextToggle(checked = visible, onCheckedChange = onVisibleChange)
        }
        SettingsRuler(
            title = "大小",
            valueLabel = String.format(Locale.US, "%.2fx", scalePercent / 100f),
            value = scalePercent / 100f,
            valueRange = PlayerLowerLayoutConfig.MIN_SCALE_PERCENT / 100f..PlayerLowerLayoutConfig.MAX_SCALE_PERCENT / 100f,
            step = 0.05f,
            majorStep = 0.5f,
            tickLabel = ::formatScaleTick,
            enabled = visible,
            compact = true,
            onValueChange = { onScaleChange((it * 100f).roundToInt()) },
        )
        if (component == PlayerLowerComponent.LYRICS) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = HifiSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "显示行数",
                    style = MicaTheme.typography.bodyMd,
                    color = if (visible) MicaTheme.colors.textSecondary else MicaTheme.colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                AccentTextChoice(
                    label = "1 行",
                    selected = lyricsLineCount == PlayerLowerLayoutConfig.SINGLE_LYRICS_LINE_COUNT,
                    enabled = visible,
                    onClick = {
                        onLyricsLineCountChange(PlayerLowerLayoutConfig.SINGLE_LYRICS_LINE_COUNT)
                    },
                )
                AccentTextChoice(
                    label = "3 行",
                    selected = lyricsLineCount == PlayerLowerLayoutConfig.THREE_LYRICS_LINE_COUNT,
                    enabled = visible,
                    onClick = {
                        onLyricsLineCountChange(PlayerLowerLayoutConfig.THREE_LYRICS_LINE_COUNT)
                    },
                )
            }
        }
    }
}

@Composable
private fun BoundaryPaddingControl(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MicaTheme.typography.bodyLg,
            color = MicaTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { onValueChange((value - 2).coerceAtLeast(PlayerLowerLayoutConfig.MIN_BOUNDARY_PADDING_DP)) },
            enabled = value > PlayerLowerLayoutConfig.MIN_BOUNDARY_PADDING_DP,
            modifier = Modifier.size(HifiSize.touchTarget),
        ) {
            Icon(Icons.Outlined.Remove, "减少$title", tint = MicaTheme.colors.textSecondary)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(width = 64.dp, height = HifiSize.touchTarget),
        ) {
            Text(
                text = "${value}dp",
                style = MicaTheme.typography.monoMd,
                color = MicaTheme.colors.accent,
                textAlign = TextAlign.Center,
            )
        }
        IconButton(
            onClick = { onValueChange((value + 2).coerceAtMost(PlayerLowerLayoutConfig.MAX_BOUNDARY_PADDING_DP)) },
            enabled = value < PlayerLowerLayoutConfig.MAX_BOUNDARY_PADDING_DP,
            modifier = Modifier.size(HifiSize.touchTarget),
        ) {
            Icon(Icons.Outlined.Add, "增加$title", tint = MicaTheme.colors.textSecondary)
        }
    }
}

@Composable
private fun SettingsRuler(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    majorStep: Float,
    tickLabel: (Float) -> String,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (compact) Modifier else Modifier.padding(horizontal = HifiSpacing.lg))
            .padding(vertical = HifiSpacing.xs),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                title,
                style = if (compact) MicaTheme.typography.bodyMd else MicaTheme.typography.bodyLg,
                color = if (enabled) MicaTheme.colors.textPrimary else MicaTheme.colors.textTertiary,
            )
            Text(
                valueLabel,
                style = MicaTheme.typography.monoMd,
                color = MicaTheme.colors.accent,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        PlaybackTuningRuler(
            value = value,
            valueRange = valueRange,
            step = step,
            majorStep = majorStep,
            tickLabel = tickLabel,
            onValueChange = { if (enabled) onValueChange(it) },
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = if (enabled) 1f else 0.38f },
        )
    }
}

private fun formatScaleTick(scale: Float): String {
    val rounded = scale.roundToInt()
    return if (kotlin.math.abs(scale - rounded) < 0.001f) {
        "${rounded}x"
    } else {
        String.format(Locale.US, "%.1fx", scale)
    }
}
