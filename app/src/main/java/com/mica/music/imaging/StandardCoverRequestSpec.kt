package com.mica.music.imaging

import coil.size.Scale
import kotlin.math.roundToInt

/**
 * Exact decode request shared by standard-player artwork preloads and display requests.
 * Unlike [CoverDecodeTarget], this path keeps hardware bitmaps for Compose-only rendering.
 */
data class StandardCoverRequestSpec(
    val widthPx: Int,
    val heightPx: Int,
    val scale: Scale,
) {
    fun memoryCacheKey(albumArtUri: String): String =
        "cover:standard:${widthPx}x$heightPx:${scale.name.lowercase()}:$albumArtUri"

    companion object {
        fun fromPixels(widthPx: Float, heightPx: Float, scale: Scale): StandardCoverRequestSpec =
            StandardCoverRequestSpec(
                widthPx = widthPx.safePixelSize(),
                heightPx = heightPx.safePixelSize(),
                scale = scale,
            )

        private fun Float.safePixelSize(): Int =
            takeIf { it.isFinite() && it > 0f }?.roundToInt()?.coerceAtLeast(1) ?: 1
    }
}
