package com.mica.music.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.mica.music.ui.motion.MicaMotion

private const val CoverLetterboxFadeMs = 480
internal const val LyricsCoverMorphEndFocus = 0.05f
private const val LyricsLayoutShiftDelayOnCloseMs = 220
internal const val ImmersiveProgressEpsilon = 0.001f

internal data class LyricsFocusTransition(
    val chromeFade: Float,
    val layoutFocus: Float,
    val layoutActive: Boolean,
    val coverEdgeOnPlaySurface: Boolean,
)

@Composable
internal fun rememberLyricsFocusTransition(
    lyricsExpanded: Boolean,
    useCoverEdgeProgress: Boolean,
    motionEnabled: Boolean,
): LyricsFocusTransition {
    val chromeFade by animateFloatAsState(
        targetValue = if (lyricsExpanded) 1f else 0f,
        animationSpec = MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationLongMs),
        label = "lyricsChromeFade",
    )
    val layoutFocus by animateFloatAsState(
        targetValue = if (lyricsExpanded) 1f else 0f,
        animationSpec = if (motionEnabled) {
            tween(
                durationMillis = MicaMotion.DurationLongMs,
                delayMillis = if (lyricsExpanded) 0 else LyricsLayoutShiftDelayOnCloseMs,
                easing = MicaMotion.Easing,
            )
        } else {
            tween(0)
        },
        label = "lyricsLayoutFocus",
    )
    val layoutActive =
        lyricsExpanded ||
            layoutFocus > ImmersiveProgressEpsilon ||
            (useCoverEdgeProgress && chromeFade > ImmersiveProgressEpsilon)
    val coverEdgeOnPlaySurface = useCoverEdgeProgress && !layoutActive

    return LyricsFocusTransition(
        chromeFade = chromeFade,
        layoutFocus = layoutFocus,
        layoutActive = layoutActive,
        coverEdgeOnPlaySurface = coverEdgeOnPlaySurface,
    )
}

@Composable
internal fun rememberFitOriginalLetterboxAlpha(
    fitOriginal: Boolean,
    lyricsExpanded: Boolean,
    lyricsChromeFade: Float,
    motionEnabled: Boolean,
): Float {
    if (!fitOriginal) return 0f

    val settledOnLyrics =
        lyricsExpanded && lyricsChromeFade >= 1f - ImmersiveProgressEpsilon

    val target = if (settledOnLyrics) 1f else 0f
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = MicaMotion.tweenFloat(motionEnabled, CoverLetterboxFadeMs),
        label = "coverLetterboxAlpha",
    )
    return animated
}
