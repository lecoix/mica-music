package com.mica.music.data.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.get
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从封面图采样主色；渐变/光晕优先用**靠下区域**，其次整体 muted 色。
 */
object CoverColorExtractor {

    const val FALLBACK_ARGB: Int = 0xFF2A2A32.toInt()

    fun fromBytes(bytes: ByteArray): Int? =
        decodeSampled(bytes)?.let { fromBitmap(it) }

    fun fromUri(context: Context, uri: Uri): Int? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            decodeSampled(stream.readBytes())?.let { fromBitmap(it) }
        }
    }.getOrNull()

    suspend fun fromUriString(context: Context, uriString: String?): Int? {
        if (uriString.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(uriString)
                    .allowHardware(false)
                    .size(256)
                    .build()
                val result = context.imageLoader.execute(request)
                val bitmap = when (val d = result.drawable) {
                    is android.graphics.drawable.BitmapDrawable -> d.bitmap
                    else -> null
                } ?: return@runCatching null
                fromBitmap(bitmap)
            }.getOrNull()
        }
    }

    /** 从封面图靠下 ~45% 与整体采样，得到适合下半屏渐变的主题色。 */
    fun fromBitmap(bitmap: Bitmap): Int {
        val scaled = scaleDown(bitmap, maxSide = 128)
        val bottomCrop = cropBottomFraction(scaled, fraction = 0.45f)
        val bottomPalette = Palette.from(bottomCrop).clearFilters().generate()
        val overallPalette = Palette.from(scaled).clearFilters().generate()

        val raw = pickPaletteColor(bottomPalette)
            ?: pickPaletteColor(overallPalette)
            ?: averageRgb(bottomCrop)
        return comfortToneArgb(raw)
    }

    private fun pickPaletteColor(palette: Palette): Int? {
        val none = 0
        // 优先柔和色，降低过艳、刺眼
        for (c in intArrayOf(
            palette.getDarkMutedColor(none),
            palette.getMutedColor(none),
            palette.getDominantColor(none),
            palette.getDarkVibrantColor(none),
            palette.getVibrantColor(none),
        )) {
            if (c != none && !isNearNeutral(c)) return c
        }
        return null
    }

    /** 扫描入库时做一次中性柔化（播放页会再按深浅色主题细调）。 */
    private fun comfortToneArgb(argb: Int): Int {
        val r = android.graphics.Color.red(argb) / 255f
        val g = android.graphics.Color.green(argb) / 255f
        val b = android.graphics.Color.blue(argb) / 255f
        val lum = 0.299f * r + 0.587f * g + 0.114f * b
        val targetLum = 0.36f
        val scale = if (lum > 0.01f) (targetLum / lum).coerceIn(0.65f, 1.28f) else 1f
        var nr = (r * scale).coerceIn(0f, 1f)
        var ng = (g * scale).coerceIn(0f, 1f)
        var nb = (b * scale).coerceIn(0f, 1f)
        val gray = lum
        nr = nr * 0.92f + gray * 0.08f
        ng = ng * 0.92f + gray * 0.08f
        nb = nb * 0.92f + gray * 0.08f
        return android.graphics.Color.rgb(
            (nr * 255).toInt(),
            (ng * 255).toInt(),
            (nb * 255).toInt(),
        )
    }

    private fun cropBottomFraction(bitmap: Bitmap, fraction: Float): Bitmap {
        val h = (bitmap.height * fraction).toInt().coerceIn(1, bitmap.height)
        return Bitmap.createBitmap(bitmap, 0, bitmap.height - h, bitmap.width, h)
    }

    private fun scaleDown(source: Bitmap, maxSide: Int): Bitmap {
        val maxDim = maxOf(source.width, source.height)
        if (maxDim <= maxSide) return source
        val scale = maxSide.toFloat() / maxDim
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = calculateInSampleSize(bounds, 128)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun calculateInSampleSize(bounds: BitmapFactory.Options, maxSide: Int): Int {
        val h = bounds.outHeight
        val w = bounds.outWidth
        var sample = 1
        while (h / sample > maxSide * 2 || w / sample > maxSide * 2) {
            sample *= 2
        }
        return sample
    }

    private fun averageRgb(bitmap: Bitmap): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val px = bitmap[x, y]
                val alpha = android.graphics.Color.alpha(px)
                if (alpha < 128) {
                    x += stepX
                    continue
                }
                r += android.graphics.Color.red(px)
                g += android.graphics.Color.green(px)
                b += android.graphics.Color.blue(px)
                n++
                x += stepX
            }
            y += stepY
        }
        if (n == 0) return FALLBACK_ARGB
        return android.graphics.Color.rgb(
            (r / n).toInt().coerceIn(0, 255),
            (g / n).toInt().coerceIn(0, 255),
            (b / n).toInt().coerceIn(0, 255),
        )
    }

    private fun isNearNeutral(argb: Int): Boolean {
        val r = android.graphics.Color.red(argb)
        val g = android.graphics.Color.green(argb)
        val b = android.graphics.Color.blue(argb)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val chroma = max - min
        if (max < 28) return true
        if (max > 238 && chroma < 22) return true
        if (chroma < 12) return true
        return false
    }
}
