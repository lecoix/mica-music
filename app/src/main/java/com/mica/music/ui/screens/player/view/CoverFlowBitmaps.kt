package com.mica.music.ui.screens.player.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.memory.MemoryCache
import com.mica.music.imaging.MicaImageLoaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object CoverFlowBitmaps {

    fun memoryBitmap(uri: String): Bitmap? {
        if (!MicaImageLoaders.isInitialized()) return null
        val cache = MicaImageLoaders.cover.memoryCache ?: return null
        return cache[MemoryCache.Key(uri)]?.bitmap
    }

    suspend fun ensureLoaded(context: Context, uri: String): Bitmap? {
        if (uri.isBlank()) return null
        memoryBitmap(uri)?.let { return it }
        val ok = MicaImageLoaders.ensureCoverCached(context, uri)
        if (!ok) return null
        return withContext(Dispatchers.Main) { memoryBitmap(uri) }
    }

    fun drawableBitmap(drawable: android.graphics.drawable.Drawable): Bitmap? =
        (drawable as? BitmapDrawable)?.bitmap
}
