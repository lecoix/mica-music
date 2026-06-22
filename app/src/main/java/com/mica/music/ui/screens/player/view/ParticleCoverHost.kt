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
    lyricsProgress: Float = 0f,
    coverCenter: Offset = Offset.Zero,
    coverHalfSize: Offset = Offset(1f, 1f),
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
            view.setPlaybackDisintegrationProgress(playbackDisintegrationProgress)
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
