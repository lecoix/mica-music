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
        private const val CompactCoverPx = 256f
        private const val LandscapeCoverFlowViewportRatio = 1.8f

        /** Stable target for list rows, mini-player covers, queue rows, and compact cards. */
        fun forCompactCover(): CoverDecodeTarget =
            fromPixels(CompactCoverPx, CompactCoverPx)

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

        /**
         * Landscape cover-flow slots occupy only part of the viewport. Decoding every lane at
         * full viewport width multiplies native bitmap memory without adding visible detail.
         *
         * @param pinFullViewport Portrait lyrics focus shrinks the slot below the landscape
         * threshold; keep the full-viewport target so decode size does not change mid-fold
         * (bitmap prune would otherwise flash solid-color side placeholders).
         */
        fun forCoverFlow(
            viewportWidthPx: Float,
            slotWidthPx: Float,
            slotHeightPx: Float,
            pinFullViewport: Boolean = false,
        ): CoverDecodeTarget =
            if (pinFullViewport) {
                forSpecialTheme(viewportWidthPx)
            } else if (
                viewportWidthPx >=
                slotWidthPx.coerceAtLeast(1f) * LandscapeCoverFlowViewportRatio
            ) {
                fromPixels(slotWidthPx, slotHeightPx)
            } else {
                forSpecialTheme(viewportWidthPx)
            }

        private fun bucket(value: Float): Int {
            val safe = value.takeIf { it.isFinite() && it > 0f } ?: SizeBucketPx.toFloat()
            return (ceil(safe / SizeBucketPx) * SizeBucketPx).toInt().coerceAtLeast(SizeBucketPx)
        }
    }
}
