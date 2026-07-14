package com.mica.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mica.music.data.PlayerLowerComponent
import com.mica.music.data.PlayerLowerLayoutConfig
import com.mica.music.ui.components.PlaybackTuningRuler
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.TextToggle
import com.mica.music.ui.screens.customLowerBaseHeightDp
import com.mica.music.ui.screens.customLowerFitScale
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import java.util.Locale
import kotlin.math.roundToInt

private const val PreviewPanelHeightDp = 420f

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
    normalized.order.forEachIndexed { index, component ->
        CustomPlayerComponentEditorRow(
            component = component,
            scalePercent = normalized.scalePercentOf(component),
            visible = normalized.isVisible(component),
            canMoveUp = index > 0,
            canMoveDown = index < normalized.order.lastIndex,
            onMoveUp = { onChange(normalized.move(component, -1)) },
            onMoveDown = { onChange(normalized.move(component, 1)) },
            onVisibleChange = { visible ->
                onChange(normalized.withVisibility(component, visible))
            },
            onScaleChange = { percent ->
                onChange(normalized.withScalePercent(component, percent))
            },
        )
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg)
            .height(360.dp)
            .background(MicaTheme.colors.surfaceCard.copy(alpha = 0.55f))
            .padding(HifiSpacing.md),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(104.dp)
                .background(MicaTheme.colors.textTertiary.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("封面", style = MicaTheme.typography.caption, color = MicaTheme.colors.textTertiary)
        }
        Spacer(Modifier.height(HifiSpacing.sm))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            val fit = customLowerFitScale(PreviewPanelHeightDp, config, visible)
            val previewRatio = maxHeight.value / PreviewPanelHeightDp
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "下半屏组件已全部隐藏",
                        style = MicaTheme.typography.caption,
                        color = MicaTheme.colors.textTertiary,
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(config.topPaddingDp.dp * fit * previewRatio))
                    visible.forEachIndexed { index, component ->
                        val scale = config.scalePercentOf(component) / 100f * fit
                        PreviewPlayerComponent(
                            component = component,
                            visualScale = scale,
                            heightDp = customLowerBaseHeightDp(component) * scale * previewRatio,
                        )
                        if (index < visible.lastIndex) {
                            Spacer(Modifier.height(config.spacingDp.dp * fit * previewRatio))
                        }
                    }
                    Spacer(Modifier.height(config.bottomPaddingDp.dp * fit * previewRatio))
                }
            }
            BoundaryDragHandle(
                label = "顶部",
                value = config.topPaddingDp,
                dragDirection = 1f,
                previewDpPerValueDp = fit * previewRatio,
                lineAtTop = true,
                onValueChange = onTopPaddingChange,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = (config.topPaddingDp * fit * previewRatio).dp),
            )
            BoundaryDragHandle(
                label = "底部",
                value = config.bottomPaddingDp,
                dragDirection = -1f,
                previewDpPerValueDp = fit * previewRatio,
                lineAtTop = false,
                onValueChange = onBottomPaddingChange,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(y = (-config.bottomPaddingDp * fit * previewRatio).dp),
            )
        }
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
                    scaleX = visualScale.coerceAtMost(1.5f)
                    scaleY = visualScale.coerceAtMost(1.5f)
                },
            contentAlignment = Alignment.Center,
        ) {
            when (component) {
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
                    Text("上一句歌词", style = MicaTheme.typography.caption, color = MicaTheme.colors.textTertiary)
                    Text("正在播放的歌词", style = MicaTheme.typography.bodyLg, color = MicaTheme.colors.textPrimary)
                    Text("下一句歌词", style = MicaTheme.typography.caption, color = MicaTheme.colors.textTertiary)
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
    visible: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onVisibleChange: (Boolean) -> Unit,
    onScaleChange: (Int) -> Unit,
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
            IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(HifiSize.touchTarget)) {
                Icon(Icons.Outlined.ArrowUpward, "上移", tint = MicaTheme.colors.textSecondary)
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(HifiSize.touchTarget)) {
                Icon(Icons.Outlined.ArrowDownward, "下移", tint = MicaTheme.colors.textSecondary)
            }
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
