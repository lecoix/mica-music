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
import com.mica.music.ui.components.PlayerCoverMaxScreenFraction

@Composable
fun NowPlayingBackground(
    coverColor: Color,
    albumArtUri: String?,
    mode: PlayerLowerBackgroundMode,
    /** 封面底边占屏高比例；仅 [PlayerLowerBackgroundMode.ARTWORK_GRADIENT] 使用。 */
    coverZoneStop: Float? = null,
    /**
     * 拍立得等主题：封面渐变只铺纯色 hold，不要径向/竖向渐变层。
     */
    artworkGradientSolidOnly: Boolean = false,
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
                solidOnly = artworkGradientSolidOnly,
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
        PlayerLowerBackgroundMode.DYNAMIC_LIGHT -> {
            DynamicLightBackground(
                albumArtUri = albumArtUri,
                coverColor = coverColor,
                mica = mica,
                modifier = modifier,
            )
        }
        PlayerLowerBackgroundMode.DYNAMIC_ARTWORK -> {
            DynamicArtworkBackgroundContainer(
                albumArtUri = albumArtUri,
                coverColor = coverColor,
                mica = mica,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun DynamicArtworkBackgroundContainer(
    albumArtUri: String?,
    coverColor: Color,
    mica: MicaSurfaceColors,
    modifier: Modifier = Modifier,
) {
    val isDark = MicaTheme.colors.isDark
    val coverAccent = PlayerBackgroundBlend.accentuateCover(coverColor, isDark)
    Box(
        modifier
            .fillMaxSize()
            .background(mica.gradientEnd),
    ) {
        DynamicArtworkBackground(
            albumArtUri = albumArtUri,
            fallbackColor = coverAccent,
            isDark = isDark,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to mica.gradientStart.copy(alpha = if (isDark) 0.08f else 0.05f),
                            0.34f to Color.Transparent,
                            0.78f to mica.gradientEnd.copy(alpha = if (isDark) 0.18f else 0.12f),
                            1f to mica.gradientEnd.copy(alpha = if (isDark) 0.38f else 0.26f),
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun DynamicLightBackground(
    albumArtUri: String?,
    coverColor: Color,
    mica: MicaSurfaceColors,
    modifier: Modifier = Modifier,
) {
    val isDark = MicaTheme.colors.isDark
    val coverAccent = PlayerBackgroundBlend.accentuateCover(coverColor, isDark)
    Box(
        modifier
            .fillMaxSize()
            .background(mica.gradientEnd),
    ) {
        DynamicLightGlBackground(
            albumArtUri = albumArtUri,
            fallbackColor = coverAccent,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to mica.gradientStart.copy(alpha = if (isDark) 0.16f else 0.10f),
                            0.30f to Color.Transparent,
                            0.72f to mica.gradientEnd.copy(alpha = if (isDark) 0.22f else 0.16f),
                            1f to mica.gradientEnd.copy(alpha = if (isDark) 0.46f else 0.34f),
                        ),
                    ),
                ),
        )
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
 * [solidOnly] 为 true 时仅铺 hold 纯色（拍立得回忆）。
 */
@Composable
internal fun ArtworkGradientBackground(
    accent: Color,
    isDark: Boolean,
    coverZoneStop: Float?,
    solidOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val junction = PlayerBackgroundBlend.artworkJunction(accent, accent, isDark)
    val peak = PlayerBackgroundBlend.artworkPeak(accent, accent, isDark)
    val hold = PlayerBackgroundBlend.artworkHold(accent, accent, isDark)

    if (solidOnly) {
        Box(
            modifier
                .fillMaxSize()
                .background(hold),
        )
        return
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(hold),
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val edge = coverZoneStop?.coerceIn(0.12f, PlayerCoverMaxScreenFraction)
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
        // Vertical cover→panel blend is drawn as a scrim ON the cover
        // ([artworkCoverScrimStops]), not behind it — avoids a hairline at the artwork edge.
    }
}

/** Junction + hold for artwork-gradient scrim / solid fill from a cover sample color. */
internal fun artworkGradientScrimColors(
    coverColor: Color,
    isDark: Boolean,
): Pair<Color, Color> {
    val accent = PlayerBackgroundBlend.accentuateCover(
        PlayerBackgroundBlend.comfortColor(coverColor, isDark),
        isDark,
    )
    return PlayerBackgroundBlend.artworkJunction(accent, accent, isDark) to
        PlayerBackgroundBlend.artworkHold(accent, accent, isDark)
}

/**
 * Full-bleed scrim over the cover: transparent at top, opaque [hold] by the cover bottom.
 *
 * Pacing mirrors the former on-cover edge fade (clear through ~upper 38%, then ease across
 * the lower half). Stops are mapped into the cover portion of the scrim via
 * [coverBottomFraction]; the extend below stays opaque [hold].
 */
internal fun artworkCoverScrimStops(
    junction: Color,
    hold: Color,
    coverBottomFraction: Float,
): Array<Pair<Float, Color>> {
    val bottom = coverBottomFraction.coerceIn(0.20f, 0.98f)
    fun atCover(t: Float): Float = (t.coerceIn(0f, 1f) * bottom).coerceIn(0f, 1f)
    // Fully opaque a hair before the geometric cover bottom (subpixel / scale safety).
    val opaqueAt = (bottom - 0.008f).coerceAtLeast(atCover(0.98f))
    return arrayOf(
        0f to Color.Transparent,
        atCover(0.38f) to Color.Transparent,
        atCover(0.58f) to junction.copy(alpha = 0.42f),
        atCover(0.76f) to junction.copy(alpha = 0.72f),
        atCover(0.90f) to hold.copy(alpha = 0.90f),
        opaqueAt to hold,
        1f to hold,
    )
}
