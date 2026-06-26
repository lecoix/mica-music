package com.mica.music.ui.theme

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mica.music.imaging.MicaImageLoaders
import com.mica.music.util.TrackSwitchPerformance

/**
 * 模糊背景源图的解码尺寸（像素）。背景最终会被 [BlurEffect] 模糊到约 120px，
 * 无需原图分辨率；降采样加载可大幅降低内存占用与解码耗时，避免把封面位图挤出 Coil 内存缓存，
 * 从而消除切歌时封面 slot 重建后那一帧的空白（模糊/渐变背景下表现为闪一下）。
 */
private const val BlurredBackgroundSourcePx = 384

/**
 * 封面模糊：全屏强模糊专辑图 + 取色晕染；Android 12+ 用 [BlurEffect]，低版本取色渐变兜底。
 */
@Composable
fun BlurredCoverBackground(
    albumArtUri: String?,
    coverColor: Color,
    mica: MicaSurfaceColors,
    dynamicLight: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isDark = MicaTheme.colors.isDark
    val accent = PlayerBackgroundBlend.accentuateCover(coverColor, isDark)
    val canShowArtwork = !albumArtUri.isNullOrBlank()
    val context = LocalContext.current
    var readyBackgroundUri by remember { mutableStateOf<String?>(null) }
    val imageReady = albumArtUri.isNullOrBlank() || albumArtUri == readyBackgroundUri
    val backgroundImageLoader = remember { MicaImageLoaders.background }
    val isFirstBackground = readyBackgroundUri == null
    // 切歌过渡：新图层未就绪前保持 alpha=0，只显示 holdover 模糊层。
    val foregroundAlpha = when {
        albumArtUri.isNullOrBlank() -> 0f
        imageReady -> 1f
        isFirstBackground -> 1f
        else -> 0f
    }

    LaunchedEffect(albumArtUri) {
        TrackSwitchPerformance.mark(
            "blur-bg-request",
            "uri=${albumArtUri?.takeLast(48)} ready=${readyBackgroundUri?.takeLast(48)}",
        )
        if (!albumArtUri.isNullOrBlank()) {
            MicaImageLoaders.preloadBackground(context, albumArtUri)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (canShowArtwork) {
            val holdoverBackgroundUri = readyBackgroundUri?.takeIf {
                !imageReady && it != albumArtUri
            }
            if (!holdoverBackgroundUri.isNullOrBlank()) {
                val holdoverKey = MicaImageLoaders.backgroundCacheKey(holdoverBackgroundUri)
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(holdoverBackgroundUri)
                        .size(BlurredBackgroundSourcePx)
                        .memoryCacheKey(holdoverKey)
                        .placeholderMemoryCacheKey(holdoverKey)
                        .crossfade(0)
                        .build(),
                    imageLoader = backgroundImageLoader,
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
            }
            val backgroundKey = MicaImageLoaders.backgroundCacheKey(albumArtUri)
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(albumArtUri)
                    .size(BlurredBackgroundSourcePx)
                    .memoryCacheKey(backgroundKey)
                    .placeholderMemoryCacheKey(backgroundKey)
                    .crossfade(0)
                    .build(),
                imageLoader = backgroundImageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = foregroundAlpha
                        scaleX = 1.22f
                        scaleY = 1.22f
                        renderEffect = BlurEffect(120f, 120f, TileMode.Clamp)
                    },
                onSuccess = {
                    TrackSwitchPerformance.mark(
                        "blur-bg-ready",
                        "uri=${albumArtUri?.takeLast(48)} alpha=$foregroundAlpha",
                    )
                    readyBackgroundUri = albumArtUri
                },
            )
            if (dynamicLight) {
                DynamicLightOverlay(
                    albumArtUri = albumArtUri,
                    foregroundAlpha = foregroundAlpha,
                    blurRadius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 96f else 0f,
                    modifier = Modifier.fillMaxSize(),
                )
            }
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

@Composable
private fun DynamicLightOverlay(
    albumArtUri: String,
    foregroundAlpha: Float,
    blurRadius: Float,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "dynamicLight")
    val topLeftRotation by transition.animateFloat(
        initialValue = -42f,
        targetValue = 42f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 35_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dynamicLightTopLeft",
    )
    val topRightRotation by transition.animateFloat(
        initialValue = 48f,
        targetValue = -48f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 40_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dynamicLightTopRight",
    )
    val bottomLeftRotation by transition.animateFloat(
        initialValue = 54f,
        targetValue = -54f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 45_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dynamicLightBottomLeft",
    )
    val bottomRightRotation by transition.animateFloat(
        initialValue = -60f,
        targetValue = 60f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 50_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dynamicLightBottomRight",
    )

    Box(
        modifier.graphicsLayer {
            alpha = foregroundAlpha * 0.36f
            renderEffect = if (blurRadius > 0f) {
                BlurEffect(blurRadius, blurRadius, TileMode.Clamp)
            } else {
                null
            }
        },
    ) {
        DynamicLightTile(
            albumArtUri = albumArtUri,
            alignment = DynamicLightTileAlignment.TopLeft,
            rotation = topLeftRotation,
        )
        DynamicLightTile(
            albumArtUri = albumArtUri,
            alignment = DynamicLightTileAlignment.TopRight,
            rotation = topRightRotation,
        )
        DynamicLightTile(
            albumArtUri = albumArtUri,
            alignment = DynamicLightTileAlignment.BottomLeft,
            rotation = bottomLeftRotation,
        )
        DynamicLightTile(
            albumArtUri = albumArtUri,
            alignment = DynamicLightTileAlignment.BottomRight,
            rotation = bottomRightRotation,
        )
    }
}

@Composable
private fun DynamicLightTile(
    albumArtUri: String,
    alignment: DynamicLightTileAlignment,
    rotation: Float,
) {
    val backgroundKey = MicaImageLoaders.backgroundCacheKey(albumArtUri)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clip(RectangleShape),
    ) {
        val offsetX = when (alignment.horizontal) {
            DynamicLightHorizontal.Left -> -maxWidth / 2f
            DynamicLightHorizontal.Right -> maxWidth / 2f
        }
        val offsetY = when (alignment.vertical) {
            DynamicLightVertical.Top -> -maxHeight / 2f
            DynamicLightVertical.Bottom -> maxHeight / 2f
        }
        val transformOrigin = TransformOrigin(
            pivotFractionX = when (alignment.horizontal) {
                DynamicLightHorizontal.Left -> 1f
                DynamicLightHorizontal.Right -> 0f
            },
            pivotFractionY = when (alignment.vertical) {
                DynamicLightVertical.Top -> 1f
                DynamicLightVertical.Bottom -> 0f
            },
        )
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(albumArtUri)
                .size(BlurredBackgroundSourcePx)
                .memoryCacheKey(backgroundKey)
                .placeholderMemoryCacheKey(backgroundKey)
                .crossfade(0)
                .build(),
            imageLoader = remember { MicaImageLoaders.background },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offsetX.toPx()
                    translationY = offsetY.toPx()
                    scaleX = 1.55f
                    scaleY = 1.55f
                    rotationZ = rotation
                    this.transformOrigin = transformOrigin
                },
        )
    }
}

private enum class DynamicLightHorizontal {
    Left,
    Right,
}

private enum class DynamicLightVertical {
    Top,
    Bottom,
}

private enum class DynamicLightTileAlignment(
    val horizontal: DynamicLightHorizontal,
    val vertical: DynamicLightVertical,
) {
    TopLeft(DynamicLightHorizontal.Left, DynamicLightVertical.Top),
    TopRight(DynamicLightHorizontal.Right, DynamicLightVertical.Top),
    BottomLeft(DynamicLightHorizontal.Left, DynamicLightVertical.Bottom),
    BottomRight(DynamicLightHorizontal.Right, DynamicLightVertical.Bottom),
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
