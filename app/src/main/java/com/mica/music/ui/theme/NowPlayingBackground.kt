package com.mica.music.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import com.mica.music.data.PlayerLowerBackgroundMode

@Composable
fun NowPlayingBackground(
    coverColor: Color,
    albumArtUri: String?,
    mode: PlayerLowerBackgroundMode,
    modifier: Modifier = Modifier,
) {
    val themeBg = MaterialTheme.colorScheme.background
    val isDark = MicaTheme.colors.isDark
    val accent = PlayerBackgroundBlend.accentuateCover(coverColor, isDark)

    when (mode) {
        PlayerLowerBackgroundMode.THEME -> {
            ThemeOnlyBackground(accent, themeBg, isDark, modifier)
        }
        PlayerLowerBackgroundMode.ARTWORK_GRADIENT -> {
            ArtworkGradientBackground(accent, themeBg, isDark, modifier)
        }
        PlayerLowerBackgroundMode.COVER_GLOW -> {
            BlurredCoverBackground(
                albumArtUri = albumArtUri,
                coverColor = coverColor,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ThemeOnlyBackground(
    accent: Color,
    theme: Color,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(theme),
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val edge = (widthPx / heightPx).coerceIn(0.32f, 0.58f)

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to if (isDark) accent else PlayerBackgroundBlend.blend(accent, theme, 0.06f),
                            (edge * 0.55f) to PlayerBackgroundBlend.blend(accent, theme, if (isDark) 0.22f else 0.28f),
                            (edge * 0.88f) to PlayerBackgroundBlend.blend(accent, theme, if (isDark) 0.55f else 0.62f),
                            edge to theme,
                            1f to theme,
                        ),
                    ),
                ),
        )
    }
}

/**
 * 封面渐变：封面底边衔接后过渡到取色，**下半屏保持该色直至屏幕底**（不收束到主题背景色）。
 */
@Composable
internal fun ArtworkGradientBackground(
    accent: Color,
    theme: Color,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val junction = PlayerBackgroundBlend.artworkJunction(accent, theme, isDark)
    val peak = PlayerBackgroundBlend.artworkPeak(accent, theme, isDark)
    val hold = PlayerBackgroundBlend.artworkHold(accent, theme, isDark)

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(hold),
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val edge = (widthPx / heightPx).coerceIn(0.32f, 0.58f)
        val coverBottom = Offset(widthPx / 2f, widthPx)

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to junction,
                            0.22f to PlayerBackgroundBlend.blend(junction, peak, 0.28f),
                            0.48f to PlayerBackgroundBlend.blend(junction, peak, 0.58f),
                            0.72f to hold,
                            1f to hold,
                        ),
                        center = coverBottom,
                        radius = widthPx * 0.88f,
                    ),
                ),
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to hold,
                            (edge - 0.015f).coerceAtLeast(0f) to hold,
                            edge to junction,
                            (edge + 0.035f) to PlayerBackgroundBlend.blend(junction, peak, 0.22f),
                            (edge + 0.08f) to PlayerBackgroundBlend.blend(junction, peak, 0.48f),
                            (edge + 0.14f) to PlayerBackgroundBlend.blend(junction, peak, 0.72f),
                            (edge + 0.20f) to peak,
                            (edge + 0.30f) to hold,
                            1f to hold,
                        ),
                    ),
                ),
        )
    }
}

internal fun artworkEdgeFadeStops(
    junction: Color,
): Array<Pair<Float, Color>> = arrayOf(
    0f to Color.Transparent,
    0.38f to Color.Transparent,
    0.58f to junction.copy(alpha = 0.42f),
    0.76f to junction.copy(alpha = 0.72f),
    0.90f to junction.copy(alpha = 0.9f),
    1f to junction,
)
