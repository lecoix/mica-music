package com.mica.music.ui.screens.player.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.Song

@Composable
internal fun CoverFlowCarouselHost(
    queue: List<Song>,
    currentIndex: Int,
    coverFlowMode: PlayerCoverFlowMode,
    foldProgress: Float,
    screenWidthPx: Float,
    coverWidthPx: Float,
    coverHeightPx: Float,
    coverStartPaddingPx: Float,
    reflectionGapPx: Float,
    cameraDistancePx: Float,
    motionEnabled: Boolean,
    coverColor: Color,
    stageActive: Boolean,
    gesturesEnabled: Boolean,
    onPlayQueueIndex: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCoverLongPress: (() -> Unit)?,
    onAspectRatioChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coverArgb = coverColor.toArgb()

    LaunchedEffect(currentIndex, queue.size, stageActive) {
        if (!stageActive) return@LaunchedEffect
        for (offset in -3..3) {
            val uri = queue.getOrNull(currentIndex + offset)?.albumArtUri ?: continue
            CoverFlowBitmaps.ensureLoaded(context, uri)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            CoverFlowCarouselView(ctx).apply {
                setMotionEnabled(motionEnabled)
                setGesturesEnabled(gesturesEnabled)
                setFallbackColor(coverArgb)
                setScreenWidthPx(screenWidthPx)
                setCoverSizePx(coverWidthPx, coverHeightPx)
                setCoverStartPaddingPx(coverStartPaddingPx)
                setReflectionGapPx(reflectionGapPx)
                setCameraDistancePx(cameraDistancePx)
                this.onPlayQueueIndex = onPlayQueueIndex
                this.onPrevious = onPrevious
                this.onNext = onNext
                this.onCoverLongPress = onCoverLongPress
                this.onCenterAspectRatio = onAspectRatioChanged
            }
        },
        update = { view ->
            view.setMotionEnabled(motionEnabled)
            view.setGesturesEnabled(gesturesEnabled)
            view.setFallbackColor(coverArgb)
            view.setScreenWidthPx(screenWidthPx)
            view.setCoverSizePx(coverWidthPx, coverHeightPx)
            view.setCoverStartPaddingPx(coverStartPaddingPx)
            view.setReflectionGapPx(reflectionGapPx)
            view.setCameraDistancePx(cameraDistancePx)
            view.setCoverFlowMode(coverFlowMode)
            view.setFoldProgress(foldProgress)
            view.onPlayQueueIndex = onPlayQueueIndex
            view.onPrevious = onPrevious
            view.onNext = onNext
            view.onCoverLongPress = onCoverLongPress
            view.onCenterAspectRatio = onAspectRatioChanged
            view.updateQueue(queue)
            if (!stageActive) {
                view.resetToIndex(currentIndex)
            } else {
                view.updateCurrentIndex(currentIndex)
            }
        },
    )
}
