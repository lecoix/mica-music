package com.mica.music.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * 封面模糊：全屏强模糊专辑图 + 取色晕染；Android 12+ 用 [BlurEffect]，低版本取色渐变兜底。
 */
@Composable
fun BlurredCoverBackground(
    albumArtUri: String?,
    coverColor: Color,
    mica: MicaSurfaceColors,
    modifier: Modifier = Modifier,
) {
    val isDark = MicaTheme.colors.isDark
    val accent = PlayerBackgroundBlend.accentuateCover(coverColor, isDark)
    val canBlurArtwork = !albumArtUri.isNullOrBlank() &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (canBlurArtwork) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(albumArtUri)
                    .crossfade(280)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.22f
                        scaleY = 1.22f
                        renderEffect = BlurEffect(120f, 120f, TileMode.Clamp)
                    },
            )
        } else {
            AmbientPaletteBackground(
                accent = accent,
                themeTop = mica.gradientStart,
                themeBottom = mica.gradientEnd,
                isDark = isDark,
                modifier = Modifier.fillMaxSize(),
            )
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val cx = with(density) { maxWidth.toPx() * 0.5f }
            val cy = with(density) { maxHeight.toPx() * 0.36f }
            val radius = with(density) { maxWidth.toPx() * 1.1f }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to accent.copy(alpha = if (isDark) 0.42f else 0.32f),
                                0.55f to accent.copy(alpha = if (isDark) 0.18f else 0.12f),
                                1f to Color.Transparent,
                            ),
                            center = Offset(cx, cy),
                            radius = radius,
                        ),
                    ),
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to mica.gradientStart.copy(alpha = if (isDark) 0.38f else 0.24f),
                            0.32f to Color.Transparent,
                            0.52f to Color.Black.copy(alpha = if (isDark) 0.12f else 0.08f),
                            0.72f to mica.gradientEnd.copy(alpha = if (isDark) 0.45f else 0.32f),
                            1f to mica.gradientEnd.copy(alpha = if (isDark) 0.72f else 0.55f),
                        ),
                    ),
                ),
        )
    }
}

/** API &lt; 31 或无封面：用专辑主色做柔和光晕底。 */
@Composable
private fun AmbientPaletteBackground(
    accent: Color,
    themeTop: Color,
    themeBottom: Color,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val hold = PlayerBackgroundBlend.artworkHold(accent, themeBottom, isDark)
    val peak = PlayerBackgroundBlend.artworkPeak(accent, themeBottom, isDark)
    Box(
        modifier.background(
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to PlayerBackgroundBlend.blend(peak, themeTop, 0.12f),
                    0.45f to hold,
                    1f to PlayerBackgroundBlend.blend(hold, themeBottom, 0.35f),
                ),
            ),
        ),
    )
}
