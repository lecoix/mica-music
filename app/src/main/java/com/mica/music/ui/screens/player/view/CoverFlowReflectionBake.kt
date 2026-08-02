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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 封面流倒影预烘焙：封面解码后离线生成翻转渐隐条带，动画帧只 [Canvas.drawBitmap]。
 * 参考 FXImageView / iCarousel「Dynamic Image Effects」思路，避免每帧 saveLayer。
 */
internal object CoverFlowReflectionBake {

    const val ENABLED = true

    internal const val CACHE_MAX_BYTES = 16 * 1024 * 1024

    private val cache = BitmapByteLruCache(CACHE_MAX_BYTES)
    private val stateLock = Any()
    private val bakeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = mutableMapOf<String, BakeRequest>()

    fun cached(uri: String, dstAspect: Float, sourceKey: String = ""): Bitmap? =
        synchronized(stateLock) {
            cache.get(cacheKey(uri, dstAspect, sourceKey))
        }

    fun evict(uri: String) {
        val prefix = "$uri#"
        val requestsToComplete = synchronized(stateLock) {
            cache.snapshot().keys
                .filter { it.startsWith(prefix) }
                .forEach { cache.remove(it) }
            inFlight.entries
                .filter { it.value.uri == uri }
                .onEach { it.value.invalidated = true }
                .also { entries -> entries.forEach { inFlight.remove(it.key) } }
                .map { it.value }
        }
        requestsToComplete.forEach { it.result.complete(null) }
    }

    fun clear() {
        val requestsToComplete = synchronized(stateLock) {
            cache.evictAll()
            val requests = inFlight.values.toList()
            requests.forEach { it.invalidated = true }
            inFlight.clear()
            requests
        }
        requestsToComplete.forEach { it.result.complete(null) }
    }

    internal fun cacheSizeBytes(): Int = synchronized(stateLock) { cache.size() * 1024 }

    suspend fun ensureBaked(
        uri: String,
        cover: Bitmap,
        dstAspect: Float,
        sourceKey: String = "",
    ): Bitmap? {
        if (!ENABLED || uri.isBlank() || dstAspect <= 0f) return null
        val key = cacheKey(uri, dstAspect, sourceKey)
        var shouldStart = false
        val request = synchronized(stateLock) {
            cache.get(key)?.let { return it }
            inFlight[key] ?: BakeRequest(
                uri = uri,
            ).also {
                inFlight[key] = it
                shouldStart = true
            }
        }
        if (!shouldStart) return request.result.await()

        bakeScope.launch {
            try {
                val baked = bake(cover, dstAspect)
                val accepted = synchronized(stateLock) {
                    if (!request.invalidated) {
                        baked?.let { cache.put(key, it) }
                        baked
                    } else {
                        null
                    }
                }
                request.result.complete(accepted)
            } catch (error: Throwable) {
                request.result.completeExceptionally(error)
            } finally {
                synchronized(stateLock) {
                    if (inFlight[key] === request) inFlight.remove(key)
                }
            }
        }
        return request.result.await()
    }

    internal fun bake(cover: Bitmap, dstAspect: Float): Bitmap? {
        if (cover.width <= 0 || cover.height <= 0 || dstAspect <= 0f) return null
        val source = softwareBitmap(cover)
        val crop = Rect()
        centerCropSrc(source.width, source.height, dstAspect, crop)
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
            source,
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
        if (source !== cover) {
            source.recycle()
        }
        return out
    }

    /** Coil 在部分机型返回 [Bitmap.Config.HARDWARE]，不能直接画进软件 [Canvas]。 */
    private fun softwareBitmap(source: Bitmap): Bitmap {
        if (source.config != Bitmap.Config.HARDWARE) return source
        return source.copy(Bitmap.Config.ARGB_8888, false) ?: source
    }

    private fun cacheKey(uri: String, dstAspect: Float, sourceKey: String): String {
        val aspectMilli = (dstAspect * 1000f).toInt()
        return "$uri#$aspectMilli#$sourceKey"
    }

    private data class BakeRequest(
        val uri: String,
        val result: CompletableDeferred<Bitmap?> = CompletableDeferred(),
        var invalidated: Boolean = false,
    )

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

/** Android's [LruCache] counts abstract units; this cache makes one unit equal one KiB. */
internal class BitmapByteLruCache(maxBytes: Int) : LruCache<String, Bitmap>(
    ((maxBytes.coerceAtLeast(1) + 1023L) / 1024L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
) {
    override fun sizeOf(key: String, value: Bitmap): Int =
        ((value.allocationByteCount.coerceAtLeast(1) + 1023L) / 1024L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
}
