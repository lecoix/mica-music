package com.mica.music.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
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
    /** 封面底边占屏高比例；仅 [PlayerLowerBackgroundMode.ARTWORK_GRADIENT] 使用。 */
    coverZoneStop: Float? = null,
    modifier: Modifier = Modifier,
) {
    val mica = rememberMicaSurfaceColors()
    val isDark = MicaTheme.colors.isDark
    val coverAccent = PlayerBackgroundBlend.accentuateCover(coverColor, isDark)

    when (mode) {
        PlayerLowerBackgroundMode.THEME -> {
            ThemeOnlyBackground(mica = mica, modifier = modifier)
        }
        PlayerLowerBackgroundMode.ARTWORK_GRADIENT -> {
            ArtworkGradientBackground(
                accent = coverAccent,
                isDark = isDark,
                coverZoneStop = coverZoneStop,
                modifier = modifier,
            )
        }
        PlayerLowerBackgroundMode.COVER_GLOW -> {
            BlurredCoverBackground(
                albumArtUri = albumArtUri,
                coverColor = coverColor,
                mica = mica,
                modifier = modifier,
            )
        }
    }
}

/** 主题色：与主页相同，仅 [rememberMicaSurfaceColors]（设置 → 云母背景 + 浅/深）。 */
@Composable
private fun ThemeOnlyBackground(
    mica: MicaSurfaceColors,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(mica.gradientStart, mica.gradientEnd),
                ),
            ),
    )
}

/**
 * 封面渐变：从封面底边径向扩散专辑取色，下半屏保持取色 **不收束到云母终点色**。
 */
@Composable
internal fun ArtworkGradientBackground(
    accent: Color,
    isDark: Boolean,
    coverZoneStop: Float?,
    modifier: Modifier = Modifier,
) {
    val junction = PlayerBackgroundBlend.artworkJunction(accent, accent, isDark)
    val peak = PlayerBackgroundBlend.artworkPeak(accent, accent, isDark)
    val hold = PlayerBackgroundBlend.artworkHold(accent, accent, isDark)

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(hold),
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val edge = coverZoneStop?.coerceIn(0.12f, 0.62f)
            ?: (widthPx / heightPx).coerceIn(0.32f, 0.58f)
        val coverBottom = Offset(widthPx / 2f, edge * heightPx)

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
                            0f to hold.copy(alpha = 0.35f),
                            (edge - 0.02f).coerceAtLeast(0f) to hold.copy(alpha = 0.55f),
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
