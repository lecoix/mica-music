package com.mica.music.ui.screens.player.view

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.mica.music.data.ParticleCoverTuning
import com.mica.music.data.Song
import com.mica.music.imaging.CoverDecodeTarget
import com.mica.music.util.TrackSwitchPerformance
import kotlinx.coroutines.delay

internal data class ParticleCoverPreviewOptions(
    val fullCoverParticles: Boolean = false,
    val fullCoverDensity: Float = 1f,
    val fullCoverBaseAlpha: Float = 0.12f,
    val fullCoverParticleAlpha: Float = 1f,
    val fullCoverParticleSize: Float = 1f,
    val fullCoverParticleSizeVariance: Float = 1f,
    val fullCoverGridStrength: Float = 0f,
    val fullCoverWobble: Float = 0f,
)

internal val ParticleCoverThemePreset = ParticleCoverPreviewOptions(
    fullCoverParticles = true,
    fullCoverDensity = 1f,
    fullCoverBaseAlpha = 0f,
    fullCoverParticleAlpha = 1.1f,
    fullCoverParticleSize = 2.4f,
    fullCoverParticleSizeVariance = 0f,
    fullCoverGridStrength = 1f,
    fullCoverWobble = 0f,
)

internal data class ParticleCoverMusicBands(
    val bass: Float = 0f,
    val mid: Float = 0f,
    val treble: Float = 0f,
)

@Composable
internal fun ParticleCoverHost(
    song: Song,
    coverDecodeTarget: CoverDecodeTarget,
    motionEnabled: Boolean,
    coverColor: Color,
    onAspectRatioChanged: (Float) -> Unit,
    onMotionActiveChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    tuning: ParticleCoverTuning = ParticleCoverTuning(),
    playbackDisintegrationProgress: Float? = null,
    musicEnergy: Float = 0f,
    musicBands: ParticleCoverMusicBands = ParticleCoverMusicBands(),
    lyricsProgress: Float = 0f,
    coverCenter: Offset = Offset.Zero,
    coverHalfSize: Offset = Offset(1f, 1f),
    previewOptions: ParticleCoverPreviewOptions = ParticleCoverPreviewOptions(),
) {
    val context = LocalContext.current
    val fallbackColor = coverColor.toArgb()
    val coverUri = song.albumArtUri.orEmpty()
    var bitmap by remember(coverUri, coverDecodeTarget) {
        mutableStateOf(CoverFlowBitmaps.memoryBitmap(coverUri, coverDecodeTarget))
    }

    LaunchedEffect(song.id, coverUri, coverDecodeTarget) {
        onAspectRatioChanged(1f)
        if (coverUri.isBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        val startedNs = SystemClock.elapsedRealtimeNanos()
        TrackSwitchPerformance.coverAsyncStarted("particle-cover")
        val cacheHit = CoverFlowBitmaps.memoryBitmap(coverUri, coverDecodeTarget) != null
        bitmap = CoverFlowBitmaps.memoryBitmap(coverUri, coverDecodeTarget)
            ?: CoverFlowBitmaps.ensureLoaded(context, coverUri, coverDecodeTarget)
        TrackSwitchPerformance.coverAsyncFinished(
            kind = "particle-cover",
            durationNs = SystemClock.elapsedRealtimeNanos() - startedNs,
            cacheHit = cacheHit,
        )
    }

    LaunchedEffect(song.id, motionEnabled) {
        if (!motionEnabled) {
            onMotionActiveChanged(false)
            return@LaunchedEffect
        }
        onMotionActiveChanged(true)
        delay(ParticleCoverRenderer.TransitionDurationMs)
        onMotionActiveChanged(false)
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ParticleCoverView(ctx).apply {
                setMotionEnabled(motionEnabled)
                setFallbackColor(fallbackColor)
                setTuning(tuning)
            }
        },
        update = { view ->
            view.setMotionEnabled(motionEnabled)
            view.setFallbackColor(fallbackColor)
            view.setTuning(tuning)
            view.setPreviewOptions(previewOptions)
            view.setPlaybackDisintegrationProgress(playbackDisintegrationProgress)
            view.setMusicEnergy(musicEnergy)
            view.setMusicBands(musicBands)
            view.setLyricsProgress(lyricsProgress)
            view.setCoverTransform(
                centerX = coverCenter.x,
                centerY = coverCenter.y,
                halfWidth = coverHalfSize.x,
                halfHeight = coverHalfSize.y,
            )
            view.setCover(song.id, bitmap)
        },
        onRelease = { view -> view.release() },
    )
}
