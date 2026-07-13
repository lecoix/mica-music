package com.mica.music.ui.theme

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

internal const val MicaWallpaperOverlayAlphaLight = 0.08f
internal const val MicaWallpaperOverlayAlphaDark = 0.42f

internal fun loadCustomWallpaperBitmap(path: String?): ImageBitmap? {
    if (path == null) return null
    val file = File(path)
    if (!file.isFile) return null
    return runCatching {
        BitmapFactory.decodeFile(path)?.asImageBitmap()
    }.getOrNull()
}

private fun isWallpaperBarAnchorValid(
    sliceTopPx: Float,
    sliceHeightPx: Float,
    viewportTopPx: Float,
    viewportHeightPx: Float,
): Boolean {
    if (sliceTopPx.isNaN() || sliceHeightPx <= 0f || viewportHeightPx <= 0f) return false
    val sliceBottomPx = sliceTopPx + sliceHeightPx
    val viewportBottomPx = viewportTopPx + viewportHeightPx
    val barCenterPx = (sliceTopPx + sliceBottomPx) / 2f
    return barCenterPx >= viewportTopPx + viewportHeightPx * 0.6f &&
        sliceBottomPx <= viewportBottomPx + 1f
}

@Composable
internal fun MicaCustomWallpaperOverlay(
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Color.Black.copy(
                    alpha = if (isDark) {
                        MicaWallpaperOverlayAlphaDark
                    } else {
                        MicaWallpaperOverlayAlphaLight
                    },
                ),
            ),
    )
}

/**
 * 按主背景视口裁出同坐标壁纸切片。用于不透明底栏：盖住列表，并与 [AnimatedMicaAppBackground] 无缝衔接。
 * 坐标未就绪时保持透明（透出下层壁纸），避免冷启动闪主题色；就绪后切回不透明切片。
 */
@Composable
internal fun MicaCustomWallpaperSlice(
    isDark: Boolean,
    fallbackColor: Color,
    modifier: Modifier = Modifier,
) {
    val preset = LocalMicaBackgroundPreset.current
    val customMica = LocalCustomMicaBackground.current
    val (gradientStart, gradientEnd) = preset.gradientColors(isDark, customMica)
    val wallpaperPath = LocalCustomWallpaperPath.current
    val wallpaperBitmap = remember(wallpaperPath) { loadCustomWallpaperBitmap(wallpaperPath) }
    val viewport = LocalWallpaperViewportState.current
    val viewportTopPx = viewport?.topPx ?: 0f
    val viewportHeightPx = viewport?.heightPx ?: 0f
    var sliceTopPx by remember { mutableFloatStateOf(Float.NaN) }
    var sliceHeightPx by remember { mutableFloatStateOf(Float.NaN) }
    var cachedTopPx by remember { mutableFloatStateOf(Float.NaN) }
    var cachedHeightPx by remember { mutableFloatStateOf(Float.NaN) }
    val scrimAlpha = if (isDark) MicaWallpaperOverlayAlphaDark else MicaWallpaperOverlayAlphaLight
    val anchorValid = isWallpaperBarAnchorValid(
        sliceTopPx = sliceTopPx,
        sliceHeightPx = sliceHeightPx,
        viewportTopPx = viewportTopPx,
        viewportHeightPx = viewportHeightPx,
    )
    val drawTopPx = if (anchorValid) sliceTopPx else cachedTopPx
    val drawHeightPx = if (anchorValid) sliceHeightPx else cachedHeightPx
    val ready = wallpaperBitmap != null &&
        isWallpaperBarAnchorValid(
            sliceTopPx = drawTopPx,
            sliceHeightPx = drawHeightPx,
            viewportTopPx = viewportTopPx,
            viewportHeightPx = viewportHeightPx,
        )

    Box(
        modifier
            .fillMaxWidth()
            .clipToBounds()
            .onGloballyPositioned { coordinates ->
                if (!coordinates.isAttached) return@onGloballyPositioned
                val bounds = coordinates.boundsInWindow()
                sliceTopPx = bounds.top
                sliceHeightPx = bounds.height
                if (isWallpaperBarAnchorValid(
                        sliceTopPx = sliceTopPx,
                        sliceHeightPx = sliceHeightPx,
                        viewportTopPx = viewportTopPx,
                        viewportHeightPx = viewportHeightPx,
                    )
                ) {
                    cachedTopPx = sliceTopPx
                    cachedHeightPx = sliceHeightPx
                }
            }
            .then(
                if (ready) {
                    Modifier.drawBehind {
                        clipRect(0f, 0f, size.width, size.height) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(gradientStart, gradientEnd),
                                    startY = 0f,
                                    endY = size.height,
                                ),
                                size = size,
                            )
                            val image = wallpaperBitmap!!
                            val viewportWidthPx = size.width
                            val imageWidthPx = image.width.toFloat()
                            val imageHeightPx = image.height.toFloat()
                            val cropScale = max(
                                viewportWidthPx / imageWidthPx,
                                viewportHeightPx / imageHeightPx,
                            )
                            val scaledWidthPx = imageWidthPx * cropScale
                            val scaledHeightPx = imageHeightPx * cropScale
                            val dstLeftPx = (viewportWidthPx - scaledWidthPx) / 2f
                            val dstTopPx = (viewportHeightPx - scaledHeightPx) / 2f
                            val sliceOffsetInViewportPx = drawTopPx - viewportTopPx
                            translate(top = -sliceOffsetInViewportPx) {
                                drawImage(
                                    image = image,
                                    dstOffset = IntOffset(dstLeftPx.roundToInt(), dstTopPx.roundToInt()),
                                    dstSize = IntSize(
                                        scaledWidthPx.roundToInt(),
                                        scaledHeightPx.roundToInt(),
                                    ),
                                )
                            }
                            drawRect(
                                color = Color.Black.copy(alpha = scrimAlpha),
                                size = Size(size.width, size.height),
                            )
                        }
                    }
                } else if (wallpaperBitmap != null) {
                    Modifier
                } else {
                    Modifier.background(fallbackColor)
                },
            ),
    )
}

@Composable
fun rememberAnimatedHifiColors(target: HifiColors): HifiColors {
    val motion = rememberMicaMotionEnabled()
    val spec: AnimationSpec<Color> = remember(motion) {
        if (motion) {
            androidx.compose.animation.core.tween(
                durationMillis = com.mica.music.ui.motion.MicaMotion.DurationMediumMs,
                easing = com.mica.music.ui.motion.MicaMotion.Easing,
            )
        } else {
            androidx.compose.animation.core.tween(0)
        }
    }
    if (!motion) return target
    return HifiColors(
        textPrimary = animateColorAsState(target.textPrimary, spec, label = "textPrimary").value,
        textSecondary = animateColorAsState(target.textSecondary, spec, label = "textSecondary").value,
        textTertiary = animateColorAsState(target.textTertiary, spec, label = "textTertiary").value,
        divider = animateColorAsState(target.divider, spec, label = "divider").value,
        surfaceGlass = animateColorAsState(target.surfaceGlass, spec, label = "surfaceGlass").value,
        surfaceCard = animateColorAsState(target.surfaceCard, spec, label = "surfaceCard").value,
        accent = animateColorAsState(target.accent, spec, label = "accent").value,
        hiRes = animateColorAsState(target.hiRes, spec, label = "hiRes").value,
        like = animateColorAsState(target.like, spec, label = "like").value,
        isDark = target.isDark,
    )
}

/** 根布局云母渐变：浅/深与预设切换时交叉淡入。 */
@Composable
fun AnimatedMicaAppBackground(modifier: Modifier = Modifier) {
    val preset = LocalMicaBackgroundPreset.current
    val isDark = MicaTheme.colors.isDark
    val motion = rememberMicaMotionEnabled()
    val spec: AnimationSpec<Color> = remember(motion) {
        if (motion) {
            androidx.compose.animation.core.tween(
                durationMillis = com.mica.music.ui.motion.MicaMotion.DurationMediumMs,
                easing = com.mica.music.ui.motion.MicaMotion.Easing,
            )
        } else {
            androidx.compose.animation.core.tween(0)
        }
    }
    val custom = LocalCustomMicaBackground.current
    val (targetStart, targetEnd) = preset.gradientColors(isDark, custom)
    val start = animateColorAsState(targetStart, spec, label = "micaGradStart").value
    val end = animateColorAsState(targetEnd, spec, label = "micaGradEnd").value
    val wallpaperPath = LocalCustomWallpaperPath.current
    val viewport = LocalWallpaperViewportState.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                viewport?.update(bounds.top, bounds.height)
            }
            .background(Brush.verticalGradient(listOf(start, end))),
    ) {
        val wallpaperFile = remember(wallpaperPath) {
            wallpaperPath?.let(::File)?.takeIf { it.isFile }
        }
        if (wallpaperFile != null) {
            AsyncImage(
                model = wallpaperFile,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            MicaCustomWallpaperOverlay(isDark)
        }
    }
}
