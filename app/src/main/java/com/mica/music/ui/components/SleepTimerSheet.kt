package com.mica.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mica.music.data.SleepTimerController
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    isActive: Boolean,
    activeRemainingLabel: String?,
    onDismiss: () -> Unit,
    onSelectMinutes: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = MicaTheme.colors.isDark
    val sheetBackground = if (isDark) HifiPalette.MicaFogDarkEnd else HifiPalette.MicaFogStart
    val defaultStep = SleepTimerController.PRESET_MINUTES.indexOf(30).coerceAtLeast(0)
    var stepIndex by remember { mutableIntStateOf(defaultStep) }
    val selectedMinutes = SleepTimerController.minutesAtStep(stepIndex)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetBackground,
        scrimColor = Color.Black.copy(alpha = if (isDark) 0.72f else 0.45f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HifiSpacing.lg)
                .padding(bottom = HifiSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(HifiSpacing.md),
        ) {
            Text(
                text = "睡眠定时",
                style = MicaTheme.typography.titleMd,
                color = MicaTheme.colors.textPrimary,
            )
            if (isActive && activeRemainingLabel != null) {
                Text(
                    text = "当前剩余 $activeRemainingLabel",
                    style = MicaTheme.typography.bodySm,
                    color = MicaTheme.colors.accent,
                )
            } else {
                Text(
                    text = "拖动选择时长，最后 30 秒渐弱并自动暂停。",
                    style = MicaTheme.typography.bodySm,
                    color = MicaTheme.colors.textSecondary,
                )
            }

            Text(
                text = SleepTimerController.presetLabel(selectedMinutes),
                style = MicaTheme.typography.titleLg,
                color = MicaTheme.colors.textPrimary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            SleepTimerStepSlider(
                stepIndex = stepIndex,
                onStepIndexChange = { stepIndex = it },
                modifier = Modifier.fillMaxWidth(),
            )

            SleepTimerActionBar(
                label = if (isActive) "更新定时" else "开始定时",
                backgroundColor = MicaTheme.colors.accent.copy(alpha = if (isDark) 0.22f else 0.14f),
                labelColor = MicaTheme.colors.accent,
                onClick = { onSelectMinutes(selectedMinutes) },
            )

            if (isActive) {
                SleepTimerActionBar(
                    label = "关闭定时",
                    backgroundColor = MicaTheme.colors.like.copy(alpha = if (isDark) 0.18f else 0.12f),
                    labelColor = MicaTheme.colors.like,
                    onClick = {
                        onCancel()
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun SleepTimerStepSlider(
    stepIndex: Int,
    onStepIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackColor = MicaTheme.colors.divider.copy(alpha = 0.55f)
    val fillColor = MicaTheme.colors.accent
    val thumbColor = MicaTheme.colors.accent
    val tickColor = MicaTheme.colors.textTertiary
    val lastStep = SleepTimerController.PRESET_MINUTES.lastIndex
    val fraction = SleepTimerController.stepFraction(stepIndex)
    var widthPx by remember { mutableFloatStateOf(0f) }

    fun xToStep(x: Float): Int {
        if (widthPx <= 0f) return stepIndex
        return SleepTimerController.snapStepIndexFromFraction(x / widthPx)
    }

    Column(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(lastStep) {
                    detectTapGestures { offset ->
                        onStepIndexChange(xToStep(offset.x))
                    }
                }
                .pointerInput(lastStep) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onStepIndexChange(xToStep(change.position.x))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackH = 4.dp.toPx()
                val cy = size.height / 2f
                val thumbW = 3.dp.toPx()
                val thumbH = 28.dp.toPx()
                val endX = size.width * fraction

                drawRect(
                    color = trackColor,
                    topLeft = Offset(0f, cy - trackH / 2f),
                    size = Size(size.width, trackH),
                )
                if (endX > 0f) {
                    drawRect(
                        color = fillColor,
                        topLeft = Offset(0f, cy - trackH / 2f),
                        size = Size(endX, trackH),
                    )
                }
                val stepCount = SleepTimerController.stepCount
                if (stepCount > 1) {
                    val tickH = 10.dp.toPx()
                    for (i in 0 until stepCount) {
                        val x = size.width * i / lastStep
                        drawRect(
                            color = tickColor,
                            topLeft = Offset(x - 0.5f, cy - tickH / 2f),
                            size = Size(1f, tickH),
                        )
                    }
                }
                drawRect(
                    color = thumbColor,
                    topLeft = Offset((endX - thumbW / 2f).coerceIn(0f, size.width - thumbW), cy - thumbH / 2f),
                    size = Size(thumbW, thumbH),
                )
            }
        }
        Spacer(Modifier.height(HifiSpacing.xs))
        Row(Modifier.fillMaxWidth()) {
            SleepTimerController.PRESET_MINUTES.forEach { minutes ->
                val label = if (minutes == 1) "1" else minutes.toString()
                Text(
                    text = label,
                    style = MicaTheme.typography.caption,
                    color = MicaTheme.colors.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SleepTimerActionBar(
    label: String,
    backgroundColor: Color,
    labelColor: Color,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, RectangleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = HifiSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MicaTheme.typography.bodyLg,
            color = labelColor,
        )
    }
}
