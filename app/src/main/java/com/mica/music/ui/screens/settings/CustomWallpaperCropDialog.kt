package com.mica.music.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mica.music.data.CustomWallpaperCrop
import com.mica.music.ui.theme.CustomWallpaperImageState
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.drawCustomWallpaper
import com.mica.music.ui.theme.rememberCustomWallpaperImage
import kotlin.math.max

@Composable
internal fun CustomWallpaperCropDialog(
    imagePath: String,
    initialCrop: CustomWallpaperCrop,
    overlayPercent: Int,
    blurDp: Int,
    onDismiss: () -> Unit,
    onConfirm: (CustomWallpaperCrop) -> Unit,
) {
    var crop by remember(initialCrop) { mutableStateOf(initialCrop.clamped()) }
    val imageState = rememberCustomWallpaperImage(imagePath)
    val image = (imageState as? CustomWallpaperImageState.Ready)?.image
    val configuration = LocalConfiguration.current
    val previewAspect = if (configuration.screenHeightDp > 0) {
        configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.toFloat()
    } else {
        9f / 16f
    }
    var previewSize by remember { mutableStateOf(Size.Zero) }
    val accentColor = MicaTheme.colors.accent
    val latestCrop = rememberUpdatedState(crop)
    val latestImage = rememberUpdatedState(image)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HifiSpacing.sm, vertical = HifiSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = Color.White)
                }
                Text(
                    text = "调整壁纸裁切",
                    style = MicaTheme.typography.bodyLg,
                    color = Color.White,
                )
                TextButton(onClick = { onConfirm(crop.clamped()) }) {
                    Text("应用", color = MicaTheme.colors.accent)
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = HifiSpacing.lg),
            ) {
                val previewWidth = minOf(maxWidth, maxHeight * previewAspect)
                val previewHeight = if (previewAspect > 0f) {
                    previewWidth / previewAspect
                } else {
                    0.dp
                }
                Box(
                    modifier = Modifier
                        .width(previewWidth)
                        .height(previewHeight)
                        .align(Alignment.Center)
                        .clip(RectangleShape),
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { previewSize = Size(it.width.toFloat(), it.height.toFloat()) }
                            .pointerInput(imagePath, previewSize) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    val loadedImage = latestImage.value
                                    if (loadedImage != null) {
                                        crop = updateWallpaperCropFromGesture(
                                            crop = latestCrop.value,
                                            imageSize = Size(
                                                loadedImage.width.toFloat(),
                                                loadedImage.height.toFloat(),
                                            ),
                                            viewportSize = previewSize,
                                            centroid = centroid,
                                            pan = pan,
                                            zoom = zoom,
                                        )
                                    }
                                }
                            }
                            .then(if (blurDp > 0) Modifier.blur(blurDp.dp) else Modifier)
                            .semantics { contentDescription = "壁纸裁切预览，可拖动和双指缩放" },
                    ) {
                        clipRect(0f, 0f, size.width, size.height) {
                            image?.let { loadedImage ->
                                drawCustomWallpaper(
                                    image = loadedImage,
                                    crop = crop,
                                )
                            }
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = overlayPercent.coerceIn(0, 100) / 100f)),
                    )
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(
                            color = accentColor,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { crop = CustomWallpaperCrop.Default }) {
                    Text("重置", color = Color.White)
                }
                Text(
                    text = "拖动移动，双指缩放",
                    style = MicaTheme.typography.caption,
                    color = Color.White.copy(alpha = 0.70f),
                )
                Spacer(Modifier.width(64.dp))
            }
            Spacer(Modifier.height(HifiSpacing.sm))
        }
    }
}

internal fun updateWallpaperCropFromGesture(
    crop: CustomWallpaperCrop,
    imageSize: Size,
    viewportSize: Size,
    centroid: Offset,
    pan: Offset,
    zoom: Float,
): CustomWallpaperCrop {
    if (imageSize.width <= 0f || imageSize.height <= 0f ||
        viewportSize.width <= 0f || viewportSize.height <= 0f
    ) {
        return crop.clamped()
    }

    val current = crop.clamped()
    val nextZoom = (current.zoom * zoom).coerceIn(
        CustomWallpaperCrop.MIN_ZOOM,
        CustomWallpaperCrop.MAX_ZOOM,
    )
    val currentScale = max(
        viewportSize.width / imageSize.width,
        viewportSize.height / imageSize.height,
    ) * current.zoom
    val nextScale = max(
        viewportSize.width / imageSize.width,
        viewportSize.height / imageSize.height,
    ) * nextZoom
    val currentMaxPanX = max(0f, (imageSize.width * currentScale - viewportSize.width) / 2f)
    val currentMaxPanY = max(0f, (imageSize.height * currentScale - viewportSize.height) / 2f)
    val nextMaxPanX = max(0f, (imageSize.width * nextScale - viewportSize.width) / 2f)
    val nextMaxPanY = max(0f, (imageSize.height * nextScale - viewportSize.height) / 2f)
    val zoomRatio = nextZoom / current.zoom
    val currentCenterX = viewportSize.width / 2f + current.offsetX * currentMaxPanX
    val currentCenterY = viewportSize.height / 2f + current.offsetY * currentMaxPanY
    val nextCenterX = centroid.x + (currentCenterX - centroid.x) * zoomRatio + pan.x
    val nextCenterY = centroid.y + (currentCenterY - centroid.y) * zoomRatio + pan.y

    return current.copy(
        zoom = nextZoom,
        offsetX = if (nextMaxPanX == 0f) 0f else {
            ((nextCenterX - viewportSize.width / 2f) / nextMaxPanX).coerceIn(-1f, 1f)
        },
        offsetY = if (nextMaxPanY == 0f) 0f else {
            ((nextCenterY - viewportSize.height / 2f) / nextMaxPanY).coerceIn(-1f, 1f)
        },
    ).clamped()
}
