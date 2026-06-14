package com.mica.music.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mica.music.util.TrackSwitchPerformance

internal const val CoverFlowDragCommitFraction = 0.2625f
internal const val StandardDragCommitFraction = CoverFlowDragCommitFraction * 0.5f
private const val StandardSwipeMaxFraction = 0.15f

@Stable
data class CoverGestureState(
    val standardSwipeOffsetFraction: Float,
    val handlers: CoverGestureHandlers,
)

@Stable
class CoverGestureHandlers internal constructor(
    private val gesturesEnabled: () -> Boolean,
    private val standardMode: () -> Boolean,
    private val screenWidthPx: () -> Float,
    private val standardSwipeFraction: () -> Float,
    private val setStandardSwipeFraction: (Float) -> Unit,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
) {
    fun onDragStart() {
        TrackSwitchPerformance.mark("standard-swipe-start")
    }

    fun onHorizontalDrag(deltaPx: Float) {
        if (!gesturesEnabled() || !standardMode()) return
        val width = screenWidthPx()
        if (width <= 0f) return
        setStandardSwipeFraction(
            (standardSwipeFraction() + deltaPx / width).coerceIn(
                -StandardSwipeMaxFraction,
                StandardSwipeMaxFraction,
            ),
        )
    }

    fun onDragEnd() {
        if (!gesturesEnabled() || !standardMode()) return
        val swipe = standardSwipeFraction()
        when {
            swipe > StandardDragCommitFraction -> {
                TrackSwitchPerformance.armTrigger("standard-swipe-prev")
                TrackSwitchPerformance.mark("standard-swipe-commit", "fraction=${"%.3f".format(swipe)} dir=prev")
                setStandardSwipeFraction(0f)
                onPrevious()
            }
            swipe < -StandardDragCommitFraction -> {
                TrackSwitchPerformance.armTrigger("standard-swipe-next")
                TrackSwitchPerformance.mark("standard-swipe-commit", "fraction=${"%.3f".format(swipe)} dir=next")
                setStandardSwipeFraction(0f)
                onNext()
            }
            else -> {
                TrackSwitchPerformance.mark("standard-swipe-cancel", "fraction=${"%.3f".format(swipe)}")
                setStandardSwipeFraction(0f)
            }
        }
    }
}

/** 标准播放页封面横向轻扫；封面流手势在 [CoverFlowCarouselView] 内处理。 */
@Composable
fun rememberCoverGestureState(
    gesturesEnabled: Boolean,
    standardMode: Boolean,
    screenWidthPx: Float,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): CoverGestureState {
    var standardSwipeFraction by remember { mutableFloatStateOf(0f) }

    val handlers = remember(
        gesturesEnabled,
        standardMode,
        screenWidthPx,
        onPrevious,
        onNext,
    ) {
        CoverGestureHandlers(
            gesturesEnabled = { gesturesEnabled },
            standardMode = { standardMode },
            screenWidthPx = { screenWidthPx },
            standardSwipeFraction = { standardSwipeFraction },
            setStandardSwipeFraction = { standardSwipeFraction = it },
            onPrevious = onPrevious,
            onNext = onNext,
        )
    }

    return CoverGestureState(
        standardSwipeOffsetFraction = standardSwipeFraction,
        handlers = handlers,
    )
}
