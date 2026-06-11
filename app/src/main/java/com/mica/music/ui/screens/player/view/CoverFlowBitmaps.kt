package com.mica.music.ui.screens.player.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import com.mica.music.imaging.MicaImageLoaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object CoverFlowBitmaps {

    fun memoryBitmap(uri: String): Bitmap? = MicaImageLoaders.coverMemoryBitmap(uri)

    suspend fun ensureLoaded(context: Context, uri: String): Bitmap? {
        if (uri.isBlank()) return null
        memoryBitmap(uri)?.let { cached ->
            if (!MicaImageLoaders.coverCacheNeedsUpgrade(uri)) return cached
            MicaImageLoaders.evictCoverMemory(uri)
        }
        val ok = MicaImageLoaders.ensureCoverCached(context, uri)
        if (!ok) return null
        return withContext(Dispatchers.Main) { memoryBitmap(uri) }
    }

    fun isPollutedThumbnail(bitmap: Bitmap): Boolean =
        maxOf(bitmap.width, bitmap.height) <= MicaImageLoaders.PollutedCoverCacheMaxSidePx

    fun drawableBitmap(drawable: android.graphics.drawable.Drawable): Bitmap? =
        (drawable as? BitmapDrawable)?.bitmap
}
