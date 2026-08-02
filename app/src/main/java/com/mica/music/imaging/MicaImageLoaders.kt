package com.mica.music.imaging

import android.content.Context
import android.graphics.Bitmap
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 模糊背景源图解码尺寸，与 [com.mica.music.ui.theme.BlurredCoverBackground] 一致。 */
private const val BackgroundSourcePx = 384

/**
 * 封面与播放页背景使用独立 [ImageLoader]，内存缓存物理隔离，
 * 避免全屏模糊背景挤掉封面位图（切歌重建当帧空白的主要来源之一）。
 */
object MicaImageLoaders {

    /**
     * 封面内存缓存中短边不超过此值时，视为降采样缩略图（主色采样 256px 等），
     * 需驱逐后重新解码全尺寸。不得用播放页槽位宽度作门槛——内嵌封面常小于槽位但仍合法。
     */
    const val PollutedCoverCacheMaxSidePx = 256
    private lateinit var appContext: Context
    private val coverLoadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val coverLoadCoordinator = CoverLoadCoordinator(coverLoadScope)

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
                    .maxSizeBytes(48 * 1024 * 1024)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve("image_cache_cover"))
                    .maxSizeBytes(100L * 1024L * 1024L)
                    .build()
            }
            .build()
        background = ImageLoader.Builder(appContext)
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizeBytes(16 * 1024 * 1024)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve("image_cache_background"))
                    .maxSizeBytes(32L * 1024L * 1024L)
                    .build()
            }
            .build()
    }

    fun backgroundCacheKey(albumArtUri: String): String = "bg:$albumArtUri"

    fun coverMemoryBitmap(uri: String): Bitmap? {
        if (!::cover.isInitialized) return null
        val cache = cover.memoryCache ?: return null
        return cache[MemoryCache.Key(uri)]?.bitmap
    }

    fun coverMemoryBitmap(uri: String, target: CoverDecodeTarget): Bitmap? {
        if (!::cover.isInitialized) return null
        val cache = cover.memoryCache ?: return null
        return cache[MemoryCache.Key(target.memoryCacheKey(uri))]?.bitmap
    }

    fun evictCoverMemory(uri: String) {
        if (!::cover.isInitialized) return
        cover.memoryCache?.remove(MemoryCache.Key(uri))
    }

    fun evictCoverMemory(uri: String, target: CoverDecodeTarget) {
        if (!::cover.isInitialized) return
        cover.memoryCache?.remove(MemoryCache.Key(target.memoryCacheKey(uri)))
    }

    fun coverCacheNeedsUpgrade(uri: String): Boolean {
        val bitmap = coverMemoryBitmap(uri) ?: return false
        return maxOf(bitmap.width, bitmap.height) <= PollutedCoverCacheMaxSidePx
    }

    private fun backgroundMemoryHit(uri: String): Boolean {
        if (!::background.isInitialized) return false
        val cache = background.memoryCache ?: return false
        return cache.get(MemoryCache.Key(backgroundCacheKey(uri))) != null
    }

    /** 将封面 URI 预载进封面专用内存缓存。 */
    fun preloadCover(context: Context, albumArtUri: String?) {
        if (albumArtUri.isNullOrBlank() || !::cover.isInitialized) return
        coverLoadScope.launch {
            runCatching { ensureCoverCached(context, albumArtUri) }
        }
    }

    fun preloadCover(
        context: Context,
        albumArtUri: String?,
        target: CoverDecodeTarget,
    ) {
        if (albumArtUri.isNullOrBlank() || !::cover.isInitialized) return
        coverLoadScope.launch {
            runCatching { ensureCoverCached(context, albumArtUri, target) }
        }
    }

    fun preloadBackground(context: Context, albumArtUri: String?) {
        if (albumArtUri.isNullOrBlank() || !::background.isInitialized) return
        background.enqueue(buildBackgroundRequest(context, albumArtUri))
    }

    /** 阻塞直到封面位图进入内存缓存（或失败），用于切歌前 gate。 */
    suspend fun ensureCoverCached(context: Context, albumArtUri: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!::cover.isInitialized) return@withContext false
            coverLoadCoordinator.execute(originalCoverLoadKey(albumArtUri)) {
                if (coverMemoryBitmap(albumArtUri) != null && !coverCacheNeedsUpgrade(albumArtUri)) {
                    true
                } else {
                    if (coverMemoryBitmap(albumArtUri) != null) {
                        evictCoverMemory(albumArtUri)
                    }
                    val result = cover.execute(buildCoverRequest(context, albumArtUri))
                    result is SuccessResult
                }
            }
        }

    suspend fun ensureCoverCached(
        context: Context,
        albumArtUri: String,
        target: CoverDecodeTarget,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!::cover.isInitialized) return@withContext false
        coverLoadCoordinator.execute(target.memoryCacheKey(albumArtUri)) {
            if (coverMemoryBitmap(albumArtUri, target) != null) {
                true
            } else {
                val result = cover.execute(buildCoverRequest(context, albumArtUri, target))
                result is SuccessResult
            }
        }
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

    private fun buildCoverRequest(
        context: Context,
        albumArtUri: String,
        target: CoverDecodeTarget,
    ): ImageRequest = ImageRequest.Builder(context)
        .data(albumArtUri)
        .size(target.widthPx, target.heightPx)
        .scale(Scale.FILL)
        .allowHardware(false)
        .memoryCacheKey(target.memoryCacheKey(albumArtUri))
        .build()

    private fun originalCoverLoadKey(albumArtUri: String): String = "cover:original:$albumArtUri"

    private fun buildBackgroundRequest(context: Context, albumArtUri: String): ImageRequest =
        ImageRequest.Builder(context)
            .data(albumArtUri)
            .size(BackgroundSourcePx)
            .memoryCacheKey(backgroundCacheKey(albumArtUri))
            .build()
}
