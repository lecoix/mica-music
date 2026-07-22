package com.mica.music.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import com.mica.music.data.SleepTimerController
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    isActive: Boolean,
    activeRemainingLabel: String?,
    onDismiss: () -> Unit,
    onSelectMinutes: (Int) -> Unit,
    onCancel: () -> Unit,
    landscape: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = MicaTheme.colors.isDark
    val sheetBackground = if (isDark) HifiPalette.MicaFogDarkEnd else HifiPalette.MicaFogStart
    val defaultStep = SleepTimerController.PRESET_MINUTES.indexOf(30).coerceAtLeast(0)
    var stepIndex by remember { mutableIntStateOf(defaultStep) }
    val selectedMinutes = SleepTimerController.minutesAtStep(stepIndex)

    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (landscape) Modifier.fillMaxHeight() else Modifier)
                .padding(horizontal = HifiSpacing.lg)
                .padding(bottom = if (landscape) HifiSpacing.lg else HifiSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(HifiSpacing.md),
        ) {
            if (landscape) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "睡眠定时",
                        style = MicaTheme.typography.titleMd,
                        color = MicaTheme.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "关闭睡眠定时",
                            tint = MicaTheme.colors.textSecondary,
                        )
                    }
                }
                HorizontalDivider(color = MicaTheme.colors.divider)
            } else {
                Text(
                    text = "睡眠定时",
                    style = MicaTheme.typography.titleMd,
                    color = MicaTheme.colors.textPrimary,
                )
            }
            if (isActive && activeRemainingLabel != null) {
                Text(
                    text = "当前剩余 $activeRemainingLabel",
                    style = MicaTheme.typography.bodySm,
                    color = MicaTheme.colors.accent,
                )
            } else {
                Text(
                    text = "滑动选择时长，最后 30 秒渐弱并自动暂停。",
                    style = MicaTheme.typography.bodySm,
                    color = MicaTheme.colors.textSecondary,
                )
            }

            SleepTimerMinuteWheel(
                initialStepIndex = defaultStep,
                onSelectedIndexChange = { stepIndex = it },
                fadeColor = sheetBackground,
                modifier = Modifier.fillMaxWidth(),
            )

            SleepTimerActionBar(
                label = if (isActive) "更新定时" else "开始定时 ($selectedMinutes 分钟)",
                backgroundColor = Color.Transparent,
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

    if (landscape) {
        PlayerSidePanel(
            onDismiss = onDismiss,
            containerColor = sheetBackground,
            scrimColor = Color.Black.copy(alpha = if (isDark) 0.42f else 0.28f),
            paneTitle = "睡眠定时",
            content = content,
        )
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = sheetBackground,
            scrimColor = Color.Black.copy(alpha = if (isDark) 0.72f else 0.45f),
        ) {
            content()
        }
    }
}

@Composable
private fun SleepTimerMinuteWheel(
    initialStepIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    fadeColor: Color,
    modifier: Modifier = Modifier,
) {
    val presets = SleepTimerController.PRESET_MINUTES
    val itemHeight = SleepTimerWheelItemHeight
    val visibleCount = SleepTimerWheelVisibleCount
    val wheelHeight = itemHeight * visibleCount
    val sidePadding = (visibleCount - 1) / 2
    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.roundToPx() }
    val listState = rememberLazyListState()
    val snapLayoutInfoProvider = remember(listState) {
        SleepTimerWheelSnapLayoutInfoProvider(listState)
    }
    val flingBehavior = rememberSnapFlingBehavior(snapLayoutInfoProvider)
    var isInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(listState, itemHeightPx, initialStepIndex) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .first { it > 0 }
        val index = initialStepIndex.coerceIn(0, presets.lastIndex)
        listState.scrollItemToCenter(index, sidePadding, itemHeightPx)
        onSelectedIndexChange(listState.resolveCenterItemIndex())
        isInitialized = true
    }

    LaunchedEffect(listState, isInitialized) {
        if (!isInitialized) return@LaunchedEffect
        snapshotFlow {
            listState.firstVisibleItemIndex
            listState.firstVisibleItemScrollOffset
            listState.resolveCenterItemIndex()
        }
            .distinctUntilChanged()
            .collect { idx ->
                if (idx in presets.indices) {
                    onSelectedIndexChange(idx)
                }
            }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(SleepTimerWheelWidth)
                .height(wheelHeight),
        ) {
            LazyColumn(
                state = listState,
                flingBehavior = flingBehavior,
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = itemHeight * sidePadding),
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(presets) { index, minutes ->
                    SleepTimerWheelItem(
                        minutes = minutes,
                        index = index,
                        listState = listState,
                        itemHeight = itemHeight,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(itemHeight * 1.15f)
                    .background(
                        Brush.verticalGradient(
                            0f to fadeColor,
                            1f to fadeColor.copy(alpha = 0f),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(itemHeight * 1.15f)
                    .background(
                        Brush.verticalGradient(
                            0f to fadeColor.copy(alpha = 0f),
                            1f to fadeColor,
                        ),
                    ),
            )
        }

        Text(
            text = "分钟",
            style = MicaTheme.typography.bodyLg,
            color = MicaTheme.colors.textSecondary,
            modifier = Modifier.padding(start = HifiSpacing.xs),
        )
    }
}

@Composable
private fun SleepTimerWheelItem(
    minutes: Int,
    index: Int,
    listState: LazyListState,
    itemHeight: Dp,
) {
    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.roundToPx() }
    val proximity by remember(index, listState) {
        derivedStateOf { listState.itemCenterProximity(index, itemHeightPx) }
    }
    val animatedProximity by animateFloatAsState(
        targetValue = proximity,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
        label = "sleepTimerWheelProximity$index",
    )
    val scale = lerp(0.86f, 1f, animatedProximity)
    val alpha = lerp(0.24f, 1f, animatedProximity)
    val textColor = lerpColor(
        MicaTheme.colors.textTertiary,
        MicaTheme.colors.textPrimary,
        animatedProximity,
    )

    Box(
        modifier = Modifier
            .height(itemHeight)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = minutes.toString(),
            style = MicaTheme.typography.bodyLg,
            color = textColor,
            modifier = Modifier.graphicsLayer {
                val visualScale = scale * SleepTimerWheelMagnification
                scaleX = visualScale
                scaleY = visualScale
                this.alpha = alpha
            },
        )
    }
}

private suspend fun LazyListState.scrollItemToCenter(
    index: Int,
    sidePadding: Int,
    itemHeightPx: Int,
) {
    if (index <= 0) {
        scrollToItem(0)
        return
    }
    scrollToItem(
        index = (index - sidePadding).coerceAtLeast(0),
        scrollOffset = itemHeightPx,
    )
}

private class SleepTimerWheelSnapLayoutInfoProvider(
    private val lazyListState: LazyListState,
) : SnapLayoutInfoProvider {
    override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float = 0f

    override fun calculateSnapOffset(velocity: Float): Float {
        val layoutInfo = lazyListState.layoutInfo
        if (layoutInfo.visibleItemsInfo.isEmpty()) return 0f
        val viewportCenter = layoutInfo.viewportStartOffset + layoutInfo.viewportSize.height / 2f
        val closest = layoutInfo.visibleItemsInfo.minByOrNull { item ->
            abs((item.offset + item.size / 2f) - viewportCenter)
        } ?: return 0f
        return (closest.offset + closest.size / 2f) - viewportCenter
    }
}

private fun LazyListState.itemCenterProximity(index: Int, itemHeightPx: Int): Float {
    val layoutInfo = layoutInfo
    if (layoutInfo.visibleItemsInfo.isEmpty()) return 0f
    val viewportCenter = layoutInfo.viewportStartOffset + layoutInfo.viewportSize.height / 2
    val itemInfo = layoutInfo.visibleItemsInfo.find { it.index == index }
    if (itemInfo != null) {
        val itemCenter = itemInfo.offset + itemInfo.size / 2
        val distance = abs(itemCenter - viewportCenter).toFloat()
        return (1f - (distance / itemHeightPx.toFloat()).coerceIn(0f, 1f))
    }
    val centerIndex = resolveCenterItemIndex()
    val indexDistance = abs(index - centerIndex)
    return (1f - indexDistance.coerceAtMost(2) / 2f).coerceIn(0f, 1f)
}

private fun LazyListState.resolveCenterItemIndex(): Int {
    val layoutInfo = layoutInfo
    if (layoutInfo.visibleItemsInfo.isEmpty()) return firstVisibleItemIndex
    val viewportCenter = layoutInfo.viewportStartOffset + layoutInfo.viewportSize.height / 2
    return layoutInfo.visibleItemsInfo.minByOrNull { item ->
        abs((item.offset + item.size / 2) - viewportCenter)
    }?.index ?: firstVisibleItemIndex
}

private const val SleepTimerWheelMagnification = 4f / 3f
private const val SleepTimerWheelVisibleCount = 3
private val SleepTimerWheelItemHeight = 44.dp * SleepTimerWheelMagnification
private val SleepTimerWheelWidth = 88.dp

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
