package com.mica.music.ui.screens.player.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import com.mica.music.ui.screens.player.CoverFlowMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面流倒影预烘焙：封面解码后离线生成翻转渐隐条带，动画帧只 [Canvas.drawBitmap]。
 * 参考 FXImageView / iCarousel「Dynamic Image Effects」思路，避免每帧 saveLayer。
 */
internal object CoverFlowReflectionBake {

    const val ENABLED = true

    private const val CACHE_MAX_ENTRIES = 32

    private val cache = LruCache<String, Bitmap>(CACHE_MAX_ENTRIES)

    fun cached(uri: String, dstAspect: Float): Bitmap? = cache.get(cacheKey(uri, dstAspect))

    fun evict(uri: String) {
        val prefix = "$uri#"
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
    }

    fun clear() = cache.evictAll()

    suspend fun ensureBaked(uri: String, cover: Bitmap, dstAspect: Float): Bitmap? {
        if (!ENABLED || uri.isBlank() || dstAspect <= 0f) return null
        val key = cacheKey(uri, dstAspect)
        cache.get(key)?.let { return it }
        val baked = withContext(Dispatchers.Default) {
            bake(cover, dstAspect)
        } ?: return null
        cache.put(key, baked)
        return baked
    }

    internal fun bake(cover: Bitmap, dstAspect: Float): Bitmap? {
        if (cover.width <= 0 || cover.height <= 0 || dstAspect <= 0f) return null
        val crop = Rect()
        centerCropSrc(cover.width, cover.height, dstAspect, crop)
        val cropW = crop.width()
        val cropH = crop.height()
        if (cropW <= 0 || cropH <= 0) return null

        val sliceH = (cropH * CoverFlowMath.ReflectionHeightFraction)
            .toInt()
            .coerceIn(1, cropH)
        val outW = cropW
        val outH = sliceH

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val srcSlice = Rect(crop.left, crop.bottom - sliceH, crop.right, crop.bottom)
        canvas.save()
        canvas.translate(0f, outH.toFloat())
        canvas.scale(1f, -1f)
        canvas.drawBitmap(
            cover,
            srcSlice,
            RectF(0f, 0f, outW.toFloat(), outH.toFloat()),
            paint,
        )
        canvas.restore()

        val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                outH.toFloat(),
                intArrayOf(
                    0xFFFFFFFF.toInt(),
                    0x8CFFFFFF.toInt(),
                    0x00FFFFFF,
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            alpha = (CoverFlowMath.ReflectionAlpha * 255f).toInt().coerceIn(0, 255)
        }
        canvas.drawRect(0f, 0f, outW.toFloat(), outH.toFloat(), gradientPaint)
        return out
    }

    private fun cacheKey(uri: String, dstAspect: Float): String {
        val aspectMilli = (dstAspect * 1000f).toInt()
        return "$uri#$aspectMilli"
    }

    private fun centerCropSrc(srcW: Int, srcH: Int, dstAspect: Float, out: Rect) {
        val srcRatio = srcW.toFloat() / srcH
        if (srcRatio > dstAspect) {
            val cropW = (srcH * dstAspect).toInt().coerceAtMost(srcW)
            val x = (srcW - cropW) / 2
            out.set(x, 0, x + cropW, srcH)
        } else {
            val cropH = (srcW / dstAspect).toInt().coerceAtMost(srcH)
            val y = (srcH - cropH) / 2
            out.set(0, y, srcW, y + cropH)
        }
    }
}
