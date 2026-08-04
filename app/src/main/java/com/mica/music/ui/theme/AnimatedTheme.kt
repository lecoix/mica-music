package com.mica.music.ui.theme

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mica.music.data.CustomWallpaperCrop
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

private fun Modifier.customWallpaperBlur(blurDp: Int): Modifier =
    if (blurDp > 0) blur(blurDp.dp) else this

internal data class CustomWallpaperDrawGeometry(
    val left: Float,
    val top: Float,
    val size: Size,
)

internal fun customWallpaperDrawGeometry(
    imageSize: Size,
    viewportSize: Size,
    crop: CustomWallpaperCrop,
): CustomWallpaperDrawGeometry? {
    if (!imageSize.isSpecified || !viewportSize.isSpecified) return null

    val imageWidth = imageSize.width
    val imageHeight = imageSize.height
    val viewportWidth = viewportSize.width
    val viewportHeight = viewportSize.height
    if (!imageWidth.isFinite() || !imageHeight.isFinite() ||
        !viewportWidth.isFinite() || !viewportHeight.isFinite() ||
        imageWidth <= 0f || imageHeight <= 0f ||
        viewportWidth <= 0f || viewportHeight <= 0f
    ) {
        return null
    }

    val normalizedCrop = crop.clamped()
    val baseScale = max(
        viewportWidth / imageWidth,
        viewportHeight / imageHeight,
    )
    val scale = baseScale * normalizedCrop.zoom
    val scaledWidth = imageWidth * scale
    val scaledHeight = imageHeight * scale
    val maxPanX = max(0f, (scaledWidth - viewportWidth) / 2f)
    val maxPanY = max(0f, (scaledHeight - viewportHeight) / 2f)
    return CustomWallpaperDrawGeometry(
        left = (viewportWidth - scaledWidth) / 2f + normalizedCrop.offsetX * maxPanX,
        top = (viewportHeight - scaledHeight) / 2f + normalizedCrop.offsetY * maxPanY,
        size = Size(scaledWidth, scaledHeight),
    )
}

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCustomWallpaper(
    image: ImageBitmap,
    crop: CustomWallpaperCrop,
    viewportSize: Size = size,
) {
    val geometry = customWallpaperDrawGeometry(
        imageSize = Size(image.width.toFloat(), image.height.toFloat()),
        viewportSize = viewportSize,
        crop = crop,
    ) ?: return

    drawImage(
        image = image,
        dstOffset = IntOffset(geometry.left.roundToInt(), geometry.top.roundToInt()),
        dstSize = IntSize(geometry.size.width.roundToInt(), geometry.size.height.roundToInt()),
    )
}

internal sealed interface CustomWallpaperImageState {
    data object Loading : CustomWallpaperImageState
    data object Failed : CustomWallpaperImageState
    data class Ready(val image: ImageBitmap) : CustomWallpaperImageState
}

@Composable
internal fun rememberCustomWallpaperImage(path: String?): CustomWallpaperImageState {
    val file = remember(path) {
        path?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
    }
    return produceState<CustomWallpaperImageState>(
        initialValue = if (file == null) {
            CustomWallpaperImageState.Failed
        } else {
            CustomWallpaperImageState.Loading
        },
        key1 = file?.absolutePath,
    ) {
        value = if (file == null) {
            CustomWallpaperImageState.Failed
        } else {
            val image = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            }
            if (image == null) {
                CustomWallpaperImageState.Failed
            } else {
                CustomWallpaperImageState.Ready(image)
            }
        }
    }.value
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

internal data class CustomWallpaperSliceLayerGeometry(
    val layerHeightPx: Float,
    val layerOffsetYPx: Float,
)

internal fun customWallpaperSliceLayerGeometry(
    sliceTopPx: Float,
    sliceHeightPx: Float,
    viewportTopPx: Float,
    viewportHeightPx: Float,
): CustomWallpaperSliceLayerGeometry? {
    if (!isWallpaperBarAnchorValid(
            sliceTopPx = sliceTopPx,
            sliceHeightPx = sliceHeightPx,
            viewportTopPx = viewportTopPx,
            viewportHeightPx = viewportHeightPx,
        )
    ) {
        return null
    }
    return CustomWallpaperSliceLayerGeometry(
        layerHeightPx = viewportHeightPx,
        layerOffsetYPx = -(sliceTopPx - viewportTopPx),
    )
}

@Composable
internal fun MicaCustomWallpaperOverlay(
    modifier: Modifier = Modifier,
) {
    val overlayAlpha = LocalCustomWallpaperOverlayPercent.current
        .coerceIn(0, 100) / 100f
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = overlayAlpha)),
    )
}

/**
 * 按主背景视口裁出同坐标壁纸切片。用于不透明底栏：盖住列表，并与 [AnimatedMicaAppBackground] 无缝衔接。
 * 坐标未就绪时保持透明（透出下层壁纸），避免冷启动闪主题色；就绪后切回不透明切片。
 */
@Composable
internal fun MicaCustomWallpaperSlice(
    fallbackColor: Color,
    modifier: Modifier = Modifier,
) {
    val wallpaperPath = LocalCustomWallpaperPath.current
    val wallpaperFile = remember(wallpaperPath) {
        wallpaperPath?.let(::File)?.takeIf { it.isFile }
    }
    val wallpaperImageState = rememberCustomWallpaperImage(wallpaperFile?.absolutePath)
    val wallpaperImage = (wallpaperImageState as? CustomWallpaperImageState.Ready)?.image
    val wallpaperFailed = wallpaperImageState is CustomWallpaperImageState.Failed
    val wallpaperCrop = LocalCustomWallpaperCrop.current
    val wallpaperBlurDp = LocalCustomWallpaperBlurDp.current
    val overlayAlpha = LocalCustomWallpaperOverlayPercent.current
        .coerceIn(0, 100) / 100f
    val viewport = LocalWallpaperViewportState.current
    val viewportTopPx = viewport?.topPx ?: 0f
    val viewportHeightPx = viewport?.heightPx ?: 0f
    var sliceTopPx by remember { mutableFloatStateOf(Float.NaN) }
    var sliceHeightPx by remember { mutableFloatStateOf(Float.NaN) }
    var cachedTopPx by remember { mutableFloatStateOf(Float.NaN) }
    var cachedHeightPx by remember { mutableFloatStateOf(Float.NaN) }
    val anchorValid = isWallpaperBarAnchorValid(
        sliceTopPx = sliceTopPx,
        sliceHeightPx = sliceHeightPx,
        viewportTopPx = viewportTopPx,
        viewportHeightPx = viewportHeightPx,
    )
    val drawTopPx = if (anchorValid) sliceTopPx else cachedTopPx
    val drawHeightPx = if (anchorValid) sliceHeightPx else cachedHeightPx
    val layerGeometry = customWallpaperSliceLayerGeometry(
            sliceTopPx = drawTopPx,
            sliceHeightPx = drawHeightPx,
            viewportTopPx = viewportTopPx,
            viewportHeightPx = viewportHeightPx,
        )
    val ready = wallpaperImage != null && layerGeometry != null
    val density = LocalDensity.current

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
                if (wallpaperFile == null || wallpaperFailed) {
                    Modifier.background(fallbackColor)
                } else {
                    Modifier
                },
            ),
    ) {
        if (ready) {
            val geometry = requireNotNull(layerGeometry)
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .requiredHeight(with(density) { geometry.layerHeightPx.toDp() })
                    .offset {
                        IntOffset(
                            x = 0,
                            y = geometry.layerOffsetYPx.roundToInt(),
                        )
                    }
                    .customWallpaperBlur(wallpaperBlurDp),
            ) {
                drawCustomWallpaper(
                    image = requireNotNull(wallpaperImage),
                    crop = wallpaperCrop,
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = overlayAlpha)),
            )
        }
    }
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
    val wallpaperFile = remember(wallpaperPath) {
        wallpaperPath?.let(::File)?.takeIf { it.isFile }
    }
    val wallpaperImageState = rememberCustomWallpaperImage(wallpaperFile?.absolutePath)
    val wallpaperImage = (wallpaperImageState as? CustomWallpaperImageState.Ready)?.image
    val wallpaperCrop = LocalCustomWallpaperCrop.current
    val wallpaperBlurDp = LocalCustomWallpaperBlurDp.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                viewport?.update(bounds.top, bounds.height)
            }
            .background(Brush.verticalGradient(listOf(start, end))),
    ) {
        if (wallpaperImage != null) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .customWallpaperBlur(wallpaperBlurDp),
            ) {
                drawCustomWallpaper(
                    image = wallpaperImage,
                    crop = wallpaperCrop,
                )
            }
            MicaCustomWallpaperOverlay()
        }
    }
}
