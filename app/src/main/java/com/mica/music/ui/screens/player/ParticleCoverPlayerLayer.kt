package com.mica.music.ui.screens.player

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.ParticleCoverTuning
import com.mica.music.data.Song
import com.mica.music.imaging.CoverDecodeTarget
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.screens.player.view.ParticleCoverHost
import com.mica.music.ui.screens.player.view.ParticleCoverThemePreset

internal const val UseNativeParticleCoverInPlayer = true

@Composable
internal fun ParticleCoverPlayerLayer(
    song: Song,
    frame: PlayerPageFrame,
    seekState: PlaybackSeekState,
    screenWidth: Dp,
    screenHeight: Dp,
    contentPadding: PaddingValues,
    motionEnabled: Boolean,
    coverColor: Color,
    tuning: ParticleCoverTuning,
    onAspectRatioChanged: (Float) -> Unit,
    onMotionActiveChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!ParticleCoverPlayerLayerModel.shouldMount(frame)) return

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val fullWidthPx = with(density) { screenWidth.coerceAtLeast(1.dp).toPx() }
    val fullHeightPx = with(density) { screenHeight.coerceAtLeast(1.dp).toPx() }
    val coverDecodeTarget = remember(fullWidthPx) {
        CoverDecodeTarget.forSpecialTheme(fullWidthPx)
    }
    val playbackDisintegrationProgress =
        ParticleCoverPlayerLayerModel.playbackDisintegrationProgress(seekState)
    val coverTransform = ParticleCoverPlayerLayerModel.coverTransform(
        frame = frame,
        lyricsProgress = frame.lyricsProgress,
        fullWidthPx = fullWidthPx,
        fullHeightPx = fullHeightPx,
        contentPadding = contentPadding,
        density = density,
        layoutDirection = layoutDirection,
    )

    ParticleCoverHost(
        song = song,
        coverDecodeTarget = coverDecodeTarget,
        motionEnabled = motionEnabled,
        coverColor = coverColor,
        tuning = tuning,
        previewOptions = ParticleCoverThemePreset,
        playbackDisintegrationProgress = playbackDisintegrationProgress,
        lyricsProgress = frame.lyricsProgress,
        coverCenter = coverTransform.center,
        coverHalfSize = coverTransform.halfSize,
        onAspectRatioChanged = onAspectRatioChanged,
        onMotionActiveChanged = onMotionActiveChanged,
        modifier = modifier.fillMaxSize(),
    )
}

private object ParticleCoverPlayerLayerModel {
    fun shouldMount(frame: PlayerPageFrame): Boolean =
        UseNativeParticleCoverInPlayer &&
            frame.particleCover.enabled

    fun playbackDisintegrationProgress(seekState: PlaybackSeekState): Float =
        (seekState.sliderValue / seekState.valueRange.endInclusive.coerceAtLeast(1f))
            .coerceIn(0f, 1f)

    fun coverTransform(
        frame: PlayerPageFrame,
        lyricsProgress: Float,
        fullWidthPx: Float,
        fullHeightPx: Float,
        contentPadding: PaddingValues,
        density: androidx.compose.ui.unit.Density,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
    ): ParticleCoverTransform {
        val cover = frame.cover
        val lyricsT = lyricsProgress.coerceIn(0f, 1f)
        // Queue keeps lyricsT=0 so the quad follows frame.cover to the header slot.
        // Lyrics uses lyricsT→1 to expand the same host into a full-screen field.
        val coverLeftPx = with(density) {
            (
                contentPadding.calculateStartPadding(layoutDirection) +
                    cover.startPadding
                ).toPx()
        }
        val coverTopPx = with(density) {
            (contentPadding.calculateTopPadding() + cover.topPadding).toPx()
        }
        val coverWidthPx = with(density) { cover.width.toPx() }
        val coverHeightPx = with(density) { cover.height.toPx() }
        val coverCenter = Offset(
            x = ((coverLeftPx + coverWidthPx / 2f) / fullWidthPx) * 2f - 1f,
            y = 1f - ((coverTopPx + coverHeightPx / 2f) / fullHeightPx) * 2f,
        )
        val coverHalfSize = Offset(
            x = coverWidthPx / fullWidthPx,
            y = coverHeightPx / fullHeightPx,
        )
        return ParticleCoverTransform(
            center = Offset(
                x = coverCenter.x * (1f - lyricsT),
                y = coverCenter.y * (1f - lyricsT),
            ),
            halfSize = Offset(
                x = coverHalfSize.x + (1f - coverHalfSize.x) * lyricsT,
                y = coverHalfSize.y + (1f - coverHalfSize.y) * lyricsT,
            ),
        )
    }
}

private data class ParticleCoverTransform(
    val center: Offset,
    val halfSize: Offset,
)
