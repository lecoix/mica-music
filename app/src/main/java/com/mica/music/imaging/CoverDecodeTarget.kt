package com.mica.music.imaging

import kotlin.math.ceil

/** Stable decoded size used by playback-page cover consumers and their preloads. */
class CoverDecodeTarget private constructor(
    val widthPx: Int,
    val heightPx: Int,
) {
    fun memoryCacheKey(albumArtUri: String): String =
        "cover:${widthPx}x$heightPx:$albumArtUri"

    override fun equals(other: Any?): Boolean =
        other is CoverDecodeTarget && widthPx == other.widthPx && heightPx == other.heightPx

    override fun hashCode(): Int = 31 * widthPx + heightPx

    companion object {
        private const val SizeBucketPx = 64

        fun fromPixels(widthPx: Float, heightPx: Float): CoverDecodeTarget =
            CoverDecodeTarget(
                widthPx = bucket(widthPx),
                heightPx = bucket(heightPx),
            )

        /**
         * Special themes shrink their center lane while active, then expand to the full-width
         * standard cover in immersive mode. Their decode target must therefore stay full-width.
         */
        fun forSpecialTheme(screenWidthPx: Float): CoverDecodeTarget =
            fromPixels(screenWidthPx, screenWidthPx)

        private fun bucket(value: Float): Int {
            val safe = value.takeIf { it.isFinite() && it > 0f } ?: SizeBucketPx.toFloat()
            return (ceil(safe / SizeBucketPx) * SizeBucketPx).toInt().coerceAtLeast(SizeBucketPx)
        }
    }
}
