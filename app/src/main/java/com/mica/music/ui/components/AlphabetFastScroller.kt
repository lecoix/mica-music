package com.mica.music.ui.components

import android.os.SystemClock
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
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mica.music.data.LibraryFastScrollIndex
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.launch
import kotlin.math.floor

private val AlphabetFastScrollLabels = ('A'..'Z').map(Char::toString) + "#"
private val AlphabetFastScrollHeight = 384.dp

@Composable
fun AlphabetFastScroller(
    labels: List<String>,
    sectionTargetsOverride: Map<String, Int>? = null,
    scrollToIndex: suspend (Int) -> Unit,
    descending: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val sectionTargets = remember(labels, sectionTargetsOverride) {
        sectionTargetsOverride?.also { targets ->
            DiagnosticLog.event(
                "LibraryUi",
                "alphabetSectionTargets cached labels=${labels.size} sections=${targets.size}",
            )
        } ?: run {
            val startedMs = SystemClock.elapsedRealtime()
            alphabetSectionTargets(labels).also { targets ->
                DiagnosticLog.event(
                    "LibraryUi",
                    "alphabetSectionTargets durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                        "labels=${labels.size} sections=${targets.size}",
                )
            }
        }
    }
    val sectionLabels = remember(descending) { alphabetFastScrollLabels(descending) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val baseIndexHeightPx = with(density) { AlphabetFastScrollHeight.toPx() }
    var viewport by remember { mutableStateOf<AlphabetIndexViewport?>(null) }
    val indexLayout = alphabetIndexLayout(viewport, baseIndexHeightPx)
    var activeSection by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            viewport = AlphabetIndexViewport(
                containerTop = coordinates.positionInRoot().y,
                containerHeight = coordinates.size.height.toFloat(),
                rootHeight = coordinates.findRootCoordinates().size.height.toFloat(),
            )
        },
    ) {
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
                .pointerInput(sectionTargets, indexLayout, sectionLabels) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var lastSection: String? = null

                        fun selectAt(y: Float) {
                            val section = alphabetSectionAt(y, indexLayout, sectionLabels) ?: return
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
                        .align(Alignment.TopEnd)
                        .offset(y = with(density) { indexLayout.top.toDp() })
                        .height(with(density) { indexLayout.height.toDp() })
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

internal data class AlphabetIndexViewport(
    val containerTop: Float,
    val containerHeight: Float,
    val rootHeight: Float,
)

internal data class AlphabetIndexLayout(val top: Float, val height: Float)

internal fun alphabetIndexLayout(
    viewport: AlphabetIndexViewport?,
    baseHeight: Float,
): AlphabetIndexLayout {
    val containerHeight = viewport?.containerHeight ?: baseHeight
    val baselineHeight = baseHeight.coerceAtMost(containerHeight)
    val baselineTop = (containerHeight - baselineHeight) / 2f
    if (viewport == null || viewport.rootHeight <= 0f) {
        return AlphabetIndexLayout(baselineTop, baselineHeight)
    }

    val baselineBottom = baselineTop + baselineHeight
    val bottomScreenGap = (viewport.rootHeight - viewport.containerTop - baselineBottom).coerceAtLeast(0f)
    val mirroredTop = (bottomScreenGap - viewport.containerTop).coerceIn(0f, baselineBottom)
    return AlphabetIndexLayout(mirroredTop, baselineBottom - mirroredTop)
}

internal fun alphabetFastScrollLabels(descending: Boolean): List<String> =
    if (descending) listOf("#") + ('Z' downTo 'A').map(Char::toString) else AlphabetFastScrollLabels

internal fun alphabetSectionTargets(labels: List<String>): Map<String, Int> {
    return LibraryFastScrollIndex.sectionTargets(labels)
}

private fun alphabetSectionAt(
    y: Float,
    indexLayout: AlphabetIndexLayout,
    sectionLabels: List<String>,
): String? {
    val slot = indexLayout.height / sectionLabels.size.toFloat()
    if (slot <= 0f) return null
    val index = floor((y - indexLayout.top).coerceIn(0f, indexLayout.height - 1f) / slot)
        .toInt()
        .coerceIn(0, sectionLabels.lastIndex)
    return sectionLabels[index]
}
