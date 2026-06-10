package com.mica.music.imaging

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 模糊背景源图解码尺寸，与 [com.mica.music.ui.theme.BlurredCoverBackground] 一致。 */
private const val BackgroundSourcePx = 384

/**
 * 封面与播放页背景使用独立 [ImageLoader]，内存缓存物理隔离，
 * 避免全屏模糊背景挤掉封面位图（切歌重建当帧空白的主要来源之一）。
 */
object MicaImageLoaders {
    private lateinit var appContext: Context

    lateinit var cover: ImageLoader
        private set

    lateinit var background: ImageLoader
        private set

    fun isInitialized(): Boolean = ::cover.isInitialized

    fun init(context: Context) {
        if (::cover.isInitialized) return
        appContext = context.applicationContext
        cover = ImageLoader.Builder(appContext)
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(0.18)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve("image_cache_cover"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .build()
        background = ImageLoader.Builder(appContext)
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(0.06)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve("image_cache_background"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .build()
    }

    fun backgroundCacheKey(albumArtUri: String): String = "bg:$albumArtUri"

    private fun coverMemoryHit(uri: String): Boolean {
        if (!::cover.isInitialized) return false
        val cache = cover.memoryCache ?: return false
        return cache.get(MemoryCache.Key(uri)) != null
    }

    private fun backgroundMemoryHit(uri: String): Boolean {
        if (!::background.isInitialized) return false
        val cache = background.memoryCache ?: return false
        return cache.get(MemoryCache.Key(backgroundCacheKey(uri))) != null
    }

    /** 将封面 URI 预载进封面专用内存缓存。 */
    fun preloadCover(context: Context, albumArtUri: String?) {
        if (albumArtUri.isNullOrBlank() || !::cover.isInitialized) return
        cover.enqueue(buildCoverRequest(context, albumArtUri))
    }

    fun preloadBackground(context: Context, albumArtUri: String?) {
        if (albumArtUri.isNullOrBlank() || !::background.isInitialized) return
        background.enqueue(buildBackgroundRequest(context, albumArtUri))
    }

    /** 阻塞直到封面位图进入内存缓存（或失败），用于切歌前 gate。 */
    suspend fun ensureCoverCached(context: Context, albumArtUri: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!::cover.isInitialized) return@withContext false
            if (coverMemoryHit(albumArtUri)) return@withContext true
            val result = cover.execute(buildCoverRequest(context, albumArtUri))
            result is SuccessResult
        }

    /** 阻塞直到模糊背景源图进入内存缓存（或失败）。 */
    suspend fun ensureBackgroundCached(context: Context, albumArtUri: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!::background.isInitialized) return@withContext false
            if (backgroundMemoryHit(albumArtUri)) return@withContext true
            val result = background.execute(buildBackgroundRequest(context, albumArtUri))
            result is SuccessResult
        }

    private fun buildCoverRequest(context: Context, albumArtUri: String): ImageRequest =
        ImageRequest.Builder(context)
            .data(albumArtUri)
            .memoryCacheKey(albumArtUri)
            .build()

    private fun buildBackgroundRequest(context: Context, albumArtUri: String): ImageRequest =
        ImageRequest.Builder(context)
            .data(albumArtUri)
            .size(BackgroundSourcePx)
            .memoryCacheKey(backgroundCacheKey(albumArtUri))
            .build()
}
