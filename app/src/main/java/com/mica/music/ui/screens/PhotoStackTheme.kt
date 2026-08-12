package com.mica.music.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mica.music.data.Song
import com.mica.music.imaging.CoverDecodeTarget
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.screens.player.PhotoStackFrame
import com.mica.music.ui.screens.player.view.PhotoStackCarouselNavigationBridge
import com.mica.music.ui.screens.player.view.PhotoStackTransitionHost

@Composable
internal fun PhotoStackThemeHost(
    queue: List<Song>,
    currentIndex: Int,
    frame: PhotoStackFrame,
    seekState: PlaybackSeekState,
    isPlaying: Boolean,
    spectrumEnabled: Boolean,
    gesturesEnabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCoverClick: (() -> Unit)?,
    onCoverLongPress: (() -> Unit)?,
    onCoverMotionActiveChanged: (Boolean) -> Unit,
    navigationBridge: PhotoStackCarouselNavigationBridge,
    onPlayQueueIndex: (Int) -> Unit,
    decodeTargetOverride: CoverDecodeTarget? = null,
    modifier: Modifier = Modifier,
) {
    val motionEnabled = rememberMicaMotionEnabled()

    PhotoStackTransitionHost(
        queue = queue,
        currentIndex = currentIndex,
        frame = frame,
        motionEnabled = motionEnabled,
        seekState = seekState,
        isPlaying = isPlaying,
        spectrumEnabled = spectrumEnabled,
        gesturesEnabled = gesturesEnabled,
        onPrevious = onPrevious,
        onNext = onNext,
        onCoverClick = onCoverClick,
        onCoverLongPress = onCoverLongPress,
        onPlayQueueIndex = onPlayQueueIndex,
        onMotionActiveChanged = onCoverMotionActiveChanged,
        navigationBridge = navigationBridge,
        decodeTargetOverride = decodeTargetOverride,
        modifier = modifier,
    )
}
