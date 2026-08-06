package com.mica.music.ui.theme

import android.graphics.Bitmap
import com.mica.music.data.MAX_CUSTOM_WALLPAPER_BLUR_DP
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Stack blur (Mario Klingemann / Tieba Lite [FastBlur] 思路).
 * 用于整屏壁纸预模糊，使主背景与底栏切片共享「先 blur 再裁切」结果。
 */
internal object CustomWallpaperStackBlur {
    /** stack blur 工作集约为 ~4×像素数；限制在 ~1.2MP 以内避免整屏 OOM。 */
    const val MAX_VIEWPORT_BLUR_PIXELS = 1_200_000

    fun blur(source: Bitmap, radius: Int, reuseInPlace: Boolean = true): Bitmap? {
        if (radius < 1) return source
        val bitmap = if (reuseInPlace) {
            source
        } else {
            source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
        }

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val widthMax = width - 1
        val heightMax = height - 1
        val pixelCount = width * height
        val diameter = radius + radius + 1

        val red = IntArray(pixelCount)
        val green = IntArray(pixelCount)
        val blue = IntArray(pixelCount)
        val minOffset = IntArray(max(width, height))

        val division = ((diameter + 1) shr 1).let { it * it }
        val lookup = IntArray(256 * division)
        for (index in lookup.indices) {
            lookup[index] = index / division
        }

        var rowOffset = 0
        val stack = Array(diameter) { IntArray(3) }

        for (y in 0 until height) {
            var redSum = 0
            var greenSum = 0
            var blueSum = 0
            var redOut = 0
            var greenOut = 0
            var blueOut = 0
            var redIn = 0
            var greenIn = 0
            var blueIn = 0

            for (offset in -radius..radius) {
                val pixel = pixels[rowOffset + min(widthMax, max(offset, 0))]
                val slot = stack[offset + radius]
                slot[0] = (pixel shr 16) and 0xFF
                slot[1] = (pixel shr 8) and 0xFF
                slot[2] = pixel and 0xFF
                val weight = radius + 1 - kotlin.math.abs(offset)
                redSum += slot[0] * weight
                greenSum += slot[1] * weight
                blueSum += slot[2] * weight
                if (offset > 0) {
                    redIn += slot[0]
                    greenIn += slot[1]
                    blueIn += slot[2]
                } else {
                    redOut += slot[0]
                    greenOut += slot[1]
                    blueOut += slot[2]
                }
            }

            var stackPointer = radius
            for (x in 0 until width) {
                red[rowOffset] = lookup[redSum]
                green[rowOffset] = lookup[greenSum]
                blue[rowOffset] = lookup[blueSum]

                redSum -= redOut
                greenSum -= greenOut
                blueSum -= blueOut

                val stackStart = stackPointer - radius + diameter
                var slot = stack[stackStart % diameter]

                redOut -= slot[0]
                greenOut -= slot[1]
                blueOut -= slot[2]

                if (y == 0) {
                    minOffset[x] = min(x + radius + 1, widthMax)
                }
                val pixel = pixels[y * width + minOffset[x]]
                slot = stack[stackStart % diameter]
                slot[0] = (pixel shr 16) and 0xFF
                slot[1] = (pixel shr 8) and 0xFF
                slot[2] = pixel and 0xFF

                redIn += slot[0]
                greenIn += slot[1]
                blueIn += slot[2]

                redSum += redIn
                greenSum += greenIn
                blueSum += blueIn

                stackPointer = (stackPointer + 1) % diameter
                slot = stack[stackPointer % diameter]

                redOut += slot[0]
                greenOut += slot[1]
                blueOut += slot[2]

                redIn -= slot[0]
                greenIn -= slot[1]
                blueIn -= slot[2]

                rowOffset++
            }
        }

        var pixelIndex = 0
        for (x in 0 until width) {
            var redSum = 0
            var greenSum = 0
            var blueSum = 0
            var redOut = 0
            var greenOut = 0
            var blueOut = 0
            var redIn = 0
            var greenIn = 0
            var blueIn = 0

            var columnOffset = -radius * width
            for (offset in -radius..radius) {
                val sampleIndex = max(0, columnOffset) + x
                val slot = stack[offset + radius]
                slot[0] = red[sampleIndex]
                slot[1] = green[sampleIndex]
                slot[2] = blue[sampleIndex]
                val weight = radius + 1 - kotlin.math.abs(offset)
                redSum += red[sampleIndex] * weight
                greenSum += green[sampleIndex] * weight
                blueSum += blue[sampleIndex] * weight
                if (offset > 0) {
                    redIn += slot[0]
                    greenIn += slot[1]
                    blueIn += slot[2]
                } else {
                    redOut += slot[0]
                    greenOut += slot[1]
                    blueOut += slot[2]
                }
                if (offset < heightMax) {
                    columnOffset += width
                }
            }

            pixelIndex = x
            var stackPointer = radius
            for (y in 0 until height) {
                pixels[pixelIndex] =
                    (0xFF shl 24) or (lookup[redSum] shl 16) or (lookup[greenSum] shl 8) or lookup[blueSum]

                redSum -= redOut
                greenSum -= greenOut
                blueSum -= blueOut

                val stackStart = stackPointer - radius + diameter
                var slot = stack[stackStart % diameter]

                redOut -= slot[0]
                greenOut -= slot[1]
                blueOut -= slot[2]

                if (x == 0) {
                    minOffset[y] = min(y + radius + 1, heightMax) * width
                }
                val sampleIndex = x + minOffset[y]
                slot = stack[stackStart % diameter]
                slot[0] = red[sampleIndex]
                slot[1] = green[sampleIndex]
                slot[2] = blue[sampleIndex]

                redIn += slot[0]
                greenIn += slot[1]
                blueIn += slot[2]

                redSum += redIn
                greenSum += greenIn
                blueSum += blueIn

                stackPointer = (stackPointer + 1) % diameter
                slot = stack[stackPointer % diameter]

                redOut += slot[0]
                greenOut += slot[1]
                blueOut += slot[2]

                redIn -= slot[0]
                greenIn -= slot[1]
                blueIn -= slot[2]

                pixelIndex += width
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * 大图先降采样再 blur，最后放大回视口尺寸（Tieba [BlurTransformation] sampling 思路）。
     */
    fun blurForViewport(source: Bitmap, blurRadius: Int): Bitmap? {
        if (blurRadius < 1) return source
        val scale = customWallpaperStackBlurDownsampleScale(source.width, source.height)
        if (scale >= 1f) {
            return blur(source, blurRadius, reuseInPlace = true)
        }

        val scaledWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val scaledRadius = (blurRadius * scale).roundToInt().coerceAtLeast(1)
        val blurred = blur(scaled, scaledRadius, reuseInPlace = true) ?: run {
            scaled.recycle()
            return null
        }
        if (scaledWidth == source.width && scaledHeight == source.height) {
            return blurred
        }
        return Bitmap.createScaledBitmap(blurred, source.width, source.height, true).also {
            blurred.recycle()
            scaled.recycle()
        }
    }
}

/**
 * 0–32dp 线性映射到 Tieba Lite 同款 stack blur 半径 0–100。
 * 参考软件滑块 max=100，progress 直接作为 [BlurTransformation] 半径（非 dp、无 25 上限）。
 */
internal fun customWallpaperStackBlurRadius(blurDp: Int): Int {
    if (blurDp <= 0) return 0
    return (blurDp * 100f / MAX_CUSTOM_WALLPAPER_BLUR_DP)
        .roundToInt()
        .coerceIn(1, 100)
}

internal fun customWallpaperStackBlurDownsampleScale(
    widthPx: Int,
    heightPx: Int,
    maxPixels: Int = CustomWallpaperStackBlur.MAX_VIEWPORT_BLUR_PIXELS,
): Float {
    if (widthPx <= 0 || heightPx <= 0) return 1f
    val pixelCount = widthPx.toLong() * heightPx
    if (pixelCount <= maxPixels) return 1f
    return sqrt(maxPixels.toFloat() / pixelCount.toFloat())
}
