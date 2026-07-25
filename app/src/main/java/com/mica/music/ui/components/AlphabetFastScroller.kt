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
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mica.music.data.LibraryFastScrollIndex
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

private val AlphabetFastScrollLabels = ('A'..'Z').map(Char::toString) + "#"
private val AlphabetFastScrollHeight = 384.dp
private const val LandscapeAlphabetMaxHeightFraction = 0.8f
private const val LandscapeAlphabetShrinkSafety = 0.96f
internal const val AlphabetFastScrollerTouchStripTag = "alphabetFastScrollerTouchStrip"

/**
 * Landscape index uses a window [Popup] that can sit above [PlayerSheetHost].
 * Home disables this while the player overlay is open.
 */
internal val LocalAlphabetFastScrollGesturesEnabled = compositionLocalOf { true }

@Composable
fun AlphabetFastScroller(
    labels: List<String>,
    sectionTargetsOverride: Map<String, Int>? = null,
    scrollToIndex: suspend (Int) -> Unit,
    descending: Boolean = false,
    fullHeightOverlay: Boolean = false,
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
    val gesturesEnabled = LocalAlphabetFastScrollGesturesEnabled.current
    val density = LocalDensity.current
    val rootView = LocalView.current.rootView
    val baseIndexHeightPx = with(density) { AlphabetFastScrollHeight.toPx() }
    var viewport by remember { mutableStateOf<AlphabetIndexViewport?>(null) }
    val indexLayout = alphabetIndexLayout(viewport, baseIndexHeightPx)
    var activeSection by remember { mutableStateOf<String?>(null) }
    SideEffect {
        if (!gesturesEnabled) activeSection = null
    }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            viewport = AlphabetIndexViewport(
                containerTop = coordinates.positionInRoot().y,
                containerTopInWindow = coordinates.positionInWindow().y,
                containerHeight = coordinates.size.height.toFloat(),
                rootHeight = coordinates.findRootCoordinates().size.height.toFloat(),
                windowHeight = rootView.height.toFloat(),
            )
        },
    ) {
        content()

        if (gesturesEnabled) {
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

            if (fullHeightOverlay) {
                val windowHeightPx = viewport?.windowHeight ?: baseIndexHeightPx
                val landscapeIndexLayout = landscapeAlphabetIndexLayout(
                    windowHeight = windowHeightPx,
                    baseHeight = baseIndexHeightPx,
                )
                val baseIndexLineHeightPx = with(density) {
                    MicaTheme.typography.monoSm.lineHeight.toPx()
                }
                val landscapeTextScale = landscapeAlphabetTextScale(
                    indexHeight = landscapeIndexLayout.height,
                    labelCount = sectionLabels.size,
                    baseLineHeight = baseIndexLineHeightPx,
                )
                Popup(
                    alignment = Alignment.TopEnd,
                    offset = IntOffset(
                        x = 0,
                        y = -(viewport?.containerTopInWindow?.roundToInt() ?: 0),
                    ),
                    properties = PopupProperties(
                        focusable = false,
                        clippingEnabled = false,
                    ),
                ) {
                    Box(
                        modifier = Modifier
                            .requiredHeight(with(density) { windowHeightPx.toDp() }),
                    ) {
                        AlphabetIndexTouchStrip(
                            sectionTargets = sectionTargets,
                            sectionLabels = sectionLabels,
                            indexLayout = landscapeIndexLayout,
                            indexLayoutInWindow = true,
                            indexTextScale = landscapeTextScale,
                            activeSection = activeSection,
                            onActiveSectionChange = { activeSection = it },
                            scrollToIndex = scrollToIndex,
                            modifier = Modifier.fillMaxHeight(),
                        )
                    }
                }
            } else {
                AlphabetIndexTouchStrip(
                    sectionTargets = sectionTargets,
                    sectionLabels = sectionLabels,
                    indexLayout = indexLayout,
                    activeSection = activeSection,
                    onActiveSectionChange = { activeSection = it },
                    scrollToIndex = scrollToIndex,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun AlphabetIndexTouchStrip(
    sectionTargets: Map<String, Int>,
    sectionLabels: List<String>,
    indexLayout: AlphabetIndexLayout?,
    indexLayoutInWindow: Boolean = false,
    indexTextScale: Float = 1f,
    activeSection: String?,
    onActiveSectionChange: (String?) -> Unit,
    scrollToIndex: suspend (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var measuredHeight by remember { mutableStateOf(0f) }
    var measuredTopInWindow by remember { mutableStateOf(0f) }
    val effectiveLayout = when {
        indexLayout == null -> AlphabetIndexLayout(top = 0f, height = measuredHeight)
        indexLayoutInWindow -> indexLayoutRelativeToTouchStrip(
            windowLayout = indexLayout,
            touchStripTopInWindow = measuredTopInWindow,
        )
        else -> indexLayout
    }
    Box(
        modifier = modifier
            .width(32.dp)
            .testTag(AlphabetFastScrollerTouchStripTag)
            .onGloballyPositioned { coordinates ->
                measuredHeight = coordinates.size.height.toFloat()
                measuredTopInWindow = coordinates.positionInWindow().y
            }
            .pointerInput(sectionTargets, effectiveLayout, sectionLabels) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastSection: String? = null

                    fun selectAt(y: Float) {
                        val section = alphabetSectionAt(y, effectiveLayout, sectionLabels) ?: return
                        onActiveSectionChange(section)
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
                    onActiveSectionChange(null)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (activeSection != null && effectiveLayout.height > 0f) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = with(density) { effectiveLayout.top.toDp() })
                    .height(with(density) { effectiveLayout.height.toDp() })
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
                        style = MicaTheme.typography.monoSm.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = (
                                MicaTheme.typography.monoSm.fontSize.value * indexTextScale
                            ).sp,
                            lineHeight = (
                                MicaTheme.typography.monoSm.lineHeight.value * indexTextScale
                            ).sp,
                        ),
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

internal data class AlphabetIndexViewport(
    val containerTop: Float,
    val containerHeight: Float,
    val rootHeight: Float,
    val containerTopInWindow: Float = containerTop,
    val windowHeight: Float = rootHeight,
)

internal data class AlphabetIndexLayout(val top: Float, val height: Float)

internal fun indexLayoutRelativeToTouchStrip(
    windowLayout: AlphabetIndexLayout,
    touchStripTopInWindow: Float,
) = windowLayout.copy(top = windowLayout.top - touchStripTopInWindow)

internal fun landscapeAlphabetIndexLayout(
    windowHeight: Float,
    baseHeight: Float,
): AlphabetIndexLayout {
    val height = minOf(
        baseHeight,
        windowHeight.coerceAtLeast(0f) * LandscapeAlphabetMaxHeightFraction,
    )
    return AlphabetIndexLayout(top = (windowHeight - height) / 2f, height = height)
}

internal fun landscapeAlphabetTextScale(
    indexHeight: Float,
    labelCount: Int,
    baseLineHeight: Float,
): Float {
    if (labelCount <= 0 || baseLineHeight <= 0f) return 1f
    val fitScale = indexHeight / (labelCount * baseLineHeight)
    return if (fitScale < 1f) {
        (fitScale * LandscapeAlphabetShrinkSafety).coerceAtLeast(0f)
    } else {
        1f
    }
}

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
