package com.mica.music.ui.screens.player.view

import android.graphics.Bitmap

/**
 * Center-crops [raw] (full video frame) into [viewWidth]×[viewHeight],
 * matching on-screen [VideoAlbumCoverView] cover fill.
 */
internal fun centerCropVideoFrame(
    raw: Bitmap,
    viewWidth: Int,
    viewHeight: Int,
    pixelWidthHeightRatio: Float = 1f,
): Bitmap {
    require(viewWidth > 0 && viewHeight > 0)
    if (raw.isRecycled || raw.width <= 0 || raw.height <= 0) return raw
    val ratio = pixelWidthHeightRatio.coerceAtLeast(0.01f)
    val logicalWidth = raw.width * ratio
    val logicalHeight = raw.height.toFloat()
    val contentAspect = logicalWidth / logicalHeight
    val viewAspect = viewWidth.toFloat() / viewHeight
    val srcX: Int
    val srcY: Int
    val srcW: Int
    val srcH: Int
    if (contentAspect > viewAspect) {
        srcH = raw.height
        srcW = (logicalHeight * viewAspect / ratio).toInt().coerceIn(1, raw.width)
        srcX = ((raw.width - srcW) / 2).coerceAtLeast(0)
        srcY = 0
    } else {
        srcW = raw.width
        srcH = (logicalWidth / viewAspect).toInt().coerceIn(1, raw.height)
        srcX = 0
        srcY = ((raw.height - srcH) / 2).coerceAtLeast(0)
    }
    val safeW = srcW.coerceAtMost(raw.width - srcX).coerceAtLeast(1)
    val safeH = srcH.coerceAtMost(raw.height - srcY).coerceAtLeast(1)
    val cropped = Bitmap.createBitmap(raw, srcX, srcY, safeW, safeH)
    if (cropped.width == viewWidth && cropped.height == viewHeight) {
        return cropped
    }
    val scaled = Bitmap.createScaledBitmap(cropped, viewWidth, viewHeight, true)
    if (scaled !== cropped && cropped !== raw) {
        cropped.recycle()
    }
    return scaled
}
