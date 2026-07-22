package com.mica.music.ui.screens.player.view

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoverFlowReflectionBakeTest {

    @Test
    fun bake_producesReflectionStrip() {
        val cover = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val baked = CoverFlowReflectionBake.bake(cover, dstAspect = 1f)
        assertNotNull(baked)
        baked!!
        assertTrue(baked.width > 0)
        assertTrue(baked.height > 0)
        assertTrue(baked.height <= cover.height)
    }

    @Test
    fun bake_wideCover_respectsAspectCrop() {
        val cover = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        val baked = CoverFlowReflectionBake.bake(cover, dstAspect = 1f)
        assertNotNull(baked)
        baked!!
        assertTrue(baked.width <= 200)
    }

    @Test
    fun byteSizedCache_evictsByBitmapAllocationSize() {
        val bitmapBytes = 64 * 64 * 4
        val cache = BitmapByteLruCache(maxBytes = bitmapBytes * 2)
        val first = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val third = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

        cache.put("first", first)
        cache.put("second", second)
        cache.put("third", third)

        assertNull(cache.get("first"))
        assertNotNull(cache.get("second"))
        assertNotNull(cache.get("third"))
        assertTrue(cache.size() * 1024 <= bitmapBytes * 2)
    }

    @Test
    fun productionReflectionCache_hasFixedByteBudget() {
        assertTrue(CoverFlowReflectionBake.CACHE_MAX_BYTES > 0)
        assertTrue(CoverFlowReflectionBake.cacheSizeBytes() <= CoverFlowReflectionBake.CACHE_MAX_BYTES)
    }
}
