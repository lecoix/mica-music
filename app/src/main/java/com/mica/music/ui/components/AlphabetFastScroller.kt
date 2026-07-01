package com.mica.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mica.music.data.AlphabeticalText
import com.mica.music.ui.theme.MicaTheme
import kotlinx.coroutines.launch
import kotlin.math.floor

private val AlphabetFastScrollLabels = ('A'..'Z').map(Char::toString) + "#"
private val AlphabetFastScrollHeight = 384.dp

@Composable
fun AlphabetFastScroller(
    labels: List<String>,
    scrollToIndex: suspend (Int) -> Unit,
    descending: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val sectionTargets = remember(labels) { alphabetSectionTargets(labels) }
    val sectionLabels = remember(descending) { alphabetFastScrollLabels(descending) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val indexHeightPx = with(density) { AlphabetFastScrollHeight.toPx() }
    var activeSection by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier) {
        content()

        activeSection?.let { section ->
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 56.dp)
                    .offset(y = (-12).dp)
                    .size(72.dp)
                    .background(
                        color = MicaTheme.colors.surfaceCard.copy(alpha = 0.92f),
                        shape = RectangleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = section,
                    style = MicaTheme.typography.display.copy(fontWeight = FontWeight.Bold),
                    color = MicaTheme.colors.accent,
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(32.dp)
                .pointerInput(sectionTargets, indexHeightPx, sectionLabels) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var lastSection: String? = null

                        fun selectAt(y: Float) {
                            val section = alphabetSectionAt(y, size.height, indexHeightPx, sectionLabels) ?: return
                            activeSection = section
                            if (section == lastSection) return
                            lastSection = section
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            sectionTargets[section]?.let { index ->
                                scope.launch { scrollToIndex(index) }
                            }
                        }

                        selectAt(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.positionChange() != androidx.compose.ui.geometry.Offset.Zero) {
                                selectAt(change.position.y)
                                change.consume()
                            }
                        }
                        activeSection = null
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (activeSection != null) {
                Column(
                    modifier = Modifier
                        .height(AlphabetFastScrollHeight)
                        .width(24.dp)
                        .background(
                            color = MicaTheme.colors.surfaceGlass,
                            shape = RectangleShape,
                        ),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    sectionLabels.forEach { section ->
                        val hasTarget = sectionTargets.containsKey(section)
                        Text(
                            text = section,
                            style = MicaTheme.typography.monoSm.copy(fontWeight = FontWeight.SemiBold),
                            color = when {
                                activeSection == section -> MicaTheme.colors.accent
                                hasTarget -> MicaTheme.colors.textSecondary
                                else -> MicaTheme.colors.textTertiary.copy(alpha = 0.45f)
                            },
                        )
                    }
                }
            }
        }
    }
}

internal fun alphabetFastScrollLabels(descending: Boolean): List<String> =
    if (descending) listOf("#") + ('Z' downTo 'A').map(Char::toString) else AlphabetFastScrollLabels

internal fun alphabetSectionTargets(labels: List<String>): Map<String, Int> {
    val targets = linkedMapOf<String, Int>()
    labels.forEachIndexed { index, label ->
        targets.putIfAbsent(AlphabeticalText.sectionFor(label), index)
    }
    return targets
}

private fun alphabetSectionAt(
    y: Float,
    height: Int,
    indexHeight: Float,
    sectionLabels: List<String>,
): String? {
    if (height <= 0) return null
    val activeHeight = indexHeight.coerceAtMost(height.toFloat())
    val activeTop = (height - activeHeight) / 2f
    val slot = activeHeight / sectionLabels.size.toFloat()
    if (slot <= 0f) return null
    val index = floor((y - activeTop).coerceIn(0f, activeHeight - 1f) / slot)
        .toInt()
        .coerceIn(0, sectionLabels.lastIndex)
    return sectionLabels[index]
}
