package com.mica.music.ui.screens.player.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import com.mica.music.imaging.MicaImageLoaders
import com.mica.music.imaging.CoverDecodeTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object CoverFlowBitmaps {

    fun memoryBitmap(uri: String, target: CoverDecodeTarget): Bitmap? =
        MicaImageLoaders.coverMemoryBitmap(uri, target)

    suspend fun ensureLoaded(
        context: Context,
        uri: String,
        target: CoverDecodeTarget,
    ): Bitmap? {
        if (uri.isBlank()) return null
        memoryBitmap(uri, target)?.let { return it }
        val ok = MicaImageLoaders.ensureCoverCached(context, uri, target)
        if (!ok) return null
        return withContext(Dispatchers.Main) { memoryBitmap(uri, target) }
    }

    fun isPollutedThumbnail(bitmap: Bitmap): Boolean =
        maxOf(bitmap.width, bitmap.height) <= MicaImageLoaders.PollutedCoverCacheMaxSidePx

    fun drawableBitmap(drawable: android.graphics.drawable.Drawable): Bitmap? =
        (drawable as? BitmapDrawable)?.bitmap
}
