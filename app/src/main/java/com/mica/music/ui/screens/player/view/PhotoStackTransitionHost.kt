package com.mica.music.ui.screens.player.view

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.mica.music.data.Song
import com.mica.music.imaging.CoverDecodeTarget
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.screens.player.PhotoStackFrame
import com.mica.music.ui.screens.player.PhotoStackImmersiveCaption
import com.mica.music.util.TrackSwitchPerformance

@Composable
internal fun PhotoStackTransitionHost(
    queue: List<Song>,
    currentIndex: Int,
    frame: PhotoStackFrame,
    motionEnabled: Boolean,
    shadowTuning: PhotoStackShadowTuning = PhotoStackShadowTuning(),
    seekState: PlaybackSeekState,
    isPlaying: Boolean,
    spectrumEnabled: Boolean,
    gesturesEnabled: Boolean,
    immersiveCaption: PhotoStackImmersiveCaption? = null,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCoverClick: (() -> Unit)? = null,
    onCoverLongPress: (() -> Unit)? = null,
    onPlayQueueIndex: (Int) -> Unit,
    onMotionActiveChanged: (Boolean) -> Unit,
    navigationBridge: PhotoStackCarouselNavigationBridge,
    decodeTargetOverride: CoverDecodeTarget? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cardWidthPx = with(density) { frame.cardWidth.toPx() }
    val cardHeightPx = with(density) { frame.cardHeight.toPx() }
    val slotWidthPx = with(density) { frame.slotWidth.toPx() }
    val slotHeightPx = with(density) { frame.slotHeight.toPx() }
    val cardTopInsetPx = with(density) { frame.cardTopInset.toPx() }
    val artworkInsetTopPx = with(density) { frame.artworkInsetTop.toPx() }
    val artworkInsetHorizontalPx = with(density) { frame.artworkInsetHorizontal.toPx() }
    val waveformHeightPx = with(density) { frame.waveformHeight.toPx() }
    val artworkSizePx = (cardWidthPx - artworkInsetHorizontalPx * 2f).coerceAtLeast(1f)
    val dynamicDecodeTarget = remember(artworkSizePx) {
        CoverDecodeTarget.fromPixels(artworkSizePx, artworkSizePx)
    }
    val decodeTarget = decodeTargetOverride ?: dynamicDecodeTarget

    LaunchedEffect(queue, currentIndex, decodeTarget) {
        for (offset in -1..3) {
            val uri = queue.getOrNull(currentIndex + offset)?.albumArtUri ?: continue
            val startedNs = SystemClock.elapsedRealtimeNanos()
            val cacheHit = CoverFlowBitmaps.memoryBitmap(uri, decodeTarget) != null
            TrackSwitchPerformance.coverAsyncStarted("photo-stack-preload")
            try {
                CoverFlowBitmaps.ensureLoaded(context, uri, decodeTarget)
            } finally {
                TrackSwitchPerformance.coverAsyncFinished(
                    kind = "photo-stack-preload",
                    durationNs = SystemClock.elapsedRealtimeNanos() - startedNs,
                    cacheHit = cacheHit,
                )
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PhotoStackTransitionView(ctx).apply {
                setMotionEnabled(motionEnabled)
                setGesturesEnabled(gesturesEnabled)
                setImmersiveProgress(frame.immersiveProgress)
                setImmersiveCaption(immersiveCaption?.title, immersiveCaption?.subtitle)
                setShadowTuning(shadowTuning)
                setDecodeTargetOverride(decodeTargetOverride)
                setFrame(
                    PhotoStackTransitionFramePx(
                        slotWidthPx = slotWidthPx,
                        slotHeightPx = slotHeightPx,
                        cardTopInsetPx = cardTopInsetPx,
                        cardWidthPx = cardWidthPx,
                        cardHeightPx = cardHeightPx,
                        artworkInsetTopPx = artworkInsetTopPx,
                        artworkInsetHorizontalPx = artworkInsetHorizontalPx,
                        waveformHeightPx = waveformHeightPx,
                    ),
                )
                setPlaybackState(
                    sliderValue = seekState.sliderValue,
                    rangeStart = seekState.valueRange.start,
                    rangeEnd = seekState.valueRange.endInclusive,
                    isPlaying = isPlaying,
                    spectrumEnabled = spectrumEnabled,
                    onSeekValueChange = seekState.onValueChange,
                    onSeekFinished = seekState.onValueChangeFinished,
                )
                setCallbacks(
                    onPlayQueueIndex = onPlayQueueIndex,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onCoverClick = onCoverClick,
                    onMotionActiveChanged = onMotionActiveChanged,
                    onCoverLongPress = onCoverLongPress,
                )
            }
        },
        update = { view ->
            navigationBridge.view = view
            val updateStartedNs = SystemClock.elapsedRealtimeNanos()
            view.setMotionEnabled(motionEnabled)
            view.setGesturesEnabled(gesturesEnabled)
            view.setImmersiveProgress(frame.immersiveProgress)
            view.setImmersiveCaption(immersiveCaption?.title, immersiveCaption?.subtitle)
            view.setShadowTuning(shadowTuning)
            view.setDecodeTargetOverride(decodeTargetOverride)
            view.setFrame(
                PhotoStackTransitionFramePx(
                    slotWidthPx = slotWidthPx,
                    slotHeightPx = slotHeightPx,
                    cardTopInsetPx = cardTopInsetPx,
                    cardWidthPx = cardWidthPx,
                    cardHeightPx = cardHeightPx,
                    artworkInsetTopPx = artworkInsetTopPx,
                    artworkInsetHorizontalPx = artworkInsetHorizontalPx,
                    waveformHeightPx = waveformHeightPx,
                ),
            )
            view.setPlaybackState(
                sliderValue = seekState.sliderValue,
                rangeStart = seekState.valueRange.start,
                rangeEnd = seekState.valueRange.endInclusive,
                isPlaying = isPlaying,
                spectrumEnabled = spectrumEnabled,
                onSeekValueChange = seekState.onValueChange,
                onSeekFinished = seekState.onValueChangeFinished,
            )
            view.setCallbacks(
                onPlayQueueIndex = onPlayQueueIndex,
                onPrevious = onPrevious,
                onNext = onNext,
                onCoverClick = onCoverClick,
                onMotionActiveChanged = onMotionActiveChanged,
                onCoverLongPress = onCoverLongPress,
            )
            view.applyHostUpdate(
                songs = queue,
                index = currentIndex,
                stageActive = true,
            )
            TrackSwitchPerformance.recordCoverHostUpdate(
                durationNs = SystemClock.elapsedRealtimeNanos() - updateStartedNs,
                queueSize = queue.size,
            )
        },
        onRelease = { view ->
            if (navigationBridge.view === view) {
                navigationBridge.view = null
            }
            view.release()
        },
    )
}
