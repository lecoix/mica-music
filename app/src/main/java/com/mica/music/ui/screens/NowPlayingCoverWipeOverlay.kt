package com.mica.music.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.Song
import com.mica.music.data.TrackSkipDirection
import com.mica.music.ui.components.SongCover
import com.mica.music.ui.components.trackWipeLayer
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.screens.player.CoverFrame
import com.mica.music.ui.theme.LocalCoverDisplayMode
import com.mica.music.ui.theme.NowPlayingBackground

internal data class PlayerCoverWipeVisual(
    val song: Song,
    val cover: CoverFrame,
    val coverColor: Color,
    val backgroundMode: PlayerLowerBackgroundMode,
    val backgroundZoneStop: Float,
    val coverDisplayMode: CoverDisplayMode,
)

@Stable
internal class PlayerCoverWipeState internal constructor(initial: PlayerCoverWipeVisual) {
    var visible by mutableStateOf(initial)
        internal set
    var outgoing by mutableStateOf<PlayerCoverWipeVisual?>(null)
        internal set
    var direction by mutableStateOf<TrackSkipDirection?>(null)
        internal set
    val progress = Animatable(1f)
}

internal fun playerCoverWipeRenderProgress(
    visibleSongId: String,
    targetSongId: String,
    outgoingPresent: Boolean,
    animationProgress: Float,
): Float = if (!outgoingPresent && visibleSongId != targetSongId) {
    // The target composition arrives before LaunchedEffect can install the outgoing visual and
    // snap the Animatable to zero. Treat that committed-but-not-started frame as progress zero so
    // the new artwork can never flash through before the wipe begins.
    0f
} else {
    animationProgress
}

private fun PlayerCoverWipeState.renderOutgoing(
    target: PlayerCoverWipeVisual,
): PlayerCoverWipeVisual? = outgoing ?: visible.takeIf { it.song.id != target.song.id }

private fun PlayerCoverWipeState.renderProgress(
    target: PlayerCoverWipeVisual,
): Float = playerCoverWipeRenderProgress(
    visibleSongId = visible.song.id,
    targetSongId = target.song.id,
    outgoingPresent = outgoing != null,
    animationProgress = progress.value,
)

internal fun Modifier.playerCoverIncomingWipe(
    state: PlayerCoverWipeState,
    target: PlayerCoverWipeVisual,
    pendingDirection: TrackSkipDirection?,
): Modifier = trackWipeLayer(
    progress = { state.renderProgress(target) },
    direction = state.direction ?: pendingDirection,
    incoming = true,
)

@Composable
internal fun rememberPlayerCoverWipeState(
    target: PlayerCoverWipeVisual,
    direction: TrackSkipDirection?,
    enabled: Boolean,
    motionEnabled: Boolean,
): PlayerCoverWipeState {
    val state = remember { PlayerCoverWipeState(target) }

    SideEffect {
        if (state.visible.song.id == target.song.id && state.visible != target) {
            state.visible = target
        }
    }

    LaunchedEffect(target.song.id, enabled) {
        if (!enabled) {
            state.visible = target
            state.outgoing = null
            state.direction = null
            state.progress.snapTo(1f)
            return@LaunchedEffect
        }
        if (state.visible.song.id == target.song.id) {
            state.visible = target
            return@LaunchedEffect
        }

        state.outgoing = state.visible
        state.visible = target
        state.direction = direction
        state.progress.snapTo(0f)
        if (motionEnabled) {
            state.progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = MicaMotion.DurationMediumMs,
                    easing = MicaMotion.Easing,
                ),
            )
        } else {
            state.progress.snapTo(1f)
        }
        state.outgoing = null
        state.direction = null
    }
    return state
}

@Composable
internal fun OutgoingCoverBackgroundWipe(
    state: PlayerCoverWipeState,
    target: PlayerCoverWipeVisual,
    pendingDirection: TrackSkipDirection?,
    modifier: Modifier = Modifier,
) {
    val outgoing = state.renderOutgoing(target) ?: return
    if (
        outgoing.backgroundMode == PlayerLowerBackgroundMode.THEME ||
        outgoing.backgroundMode == PlayerLowerBackgroundMode.DYNAMIC_LIGHT ||
        outgoing.backgroundMode == PlayerLowerBackgroundMode.DYNAMIC_ARTWORK
    ) {
        return
    }

    Box(
        modifier
            .fillMaxSize()
            .trackWipeLayer(
                progress = { state.renderProgress(target) },
                direction = state.direction ?: pendingDirection,
                incoming = false,
            ),
    ) {
        NowPlayingBackground(
            coverColor = outgoing.coverColor,
            albumArtUri = outgoing.song.albumArtUri,
            mode = outgoing.backgroundMode,
            coverZoneStop = outgoing.backgroundZoneStop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun OutgoingCoverArtworkWipe(
    state: PlayerCoverWipeState,
    target: PlayerCoverWipeVisual,
    pendingDirection: TrackSkipDirection?,
    contentPadding: PaddingValues,
    coverContentAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val outgoing = state.renderOutgoing(target) ?: return
    Box(
        modifier
            .fillMaxSize()
            .trackWipeLayer(
                progress = { state.renderProgress(target) },
                direction = state.direction ?: pendingDirection,
                incoming = false,
            )
            .padding(contentPadding),
    ) {
        CompositionLocalProvider(LocalCoverDisplayMode provides outgoing.coverDisplayMode) {
            SongCover(
                albumArtUri = outgoing.song.albumArtUri,
                fallbackColor = outgoing.coverColor,
                contentDescription = null,
                modifier = Modifier
                    .offset(
                        x = outgoing.cover.startPadding,
                        y = outgoing.cover.topPadding,
                    )
                    .size(outgoing.cover.width, outgoing.cover.height)
                    .graphicsLayer { alpha = coverContentAlpha },
                letterboxAlpha = outgoing.cover.letterboxAlpha,
                crossfadeMillis = 0,
                publishHoldoverOnSuccess = false,
                allowPreviousImageUnderlay = false,
            )
        }
    }
}
