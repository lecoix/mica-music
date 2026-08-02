package com.mica.music.ui.screens.player.view

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    @Test
    fun ensureBaked_separatesDecodedSourceTargets() {
        val uri = "content://cover/targeted"
        val smallKey = "cover:64x64:$uri"
        val largeKey = "cover:256x256:$uri"
        val smallCover = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val largeCover = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)

        try {
            CoverFlowReflectionBake.clear()
            val small = runBlocking {
                CoverFlowReflectionBake.ensureBaked(uri, smallCover, 1f, smallKey)
            }
            val large = runBlocking {
                CoverFlowReflectionBake.ensureBaked(uri, largeCover, 1f, largeKey)
            }

            assertNotNull(small)
            assertNotNull(large)
            assertTrue(small!!.width < large!!.width)
            assertNotNull(CoverFlowReflectionBake.cached(uri, 1f, smallKey))
            assertNotNull(CoverFlowReflectionBake.cached(uri, 1f, largeKey))
        } finally {
            CoverFlowReflectionBake.clear()
            smallCover.recycle()
            largeCover.recycle()
        }
    }

    @Test
    fun ensureBaked_sharesSameInFlightResult() {
        val uri = "content://cover/in-flight"
        val sourceKey = "cover:1024x1024:$uri"
        val cover = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)

        try {
            CoverFlowReflectionBake.clear()
            val results = runBlocking {
                val first = async(Dispatchers.Default) {
                    CoverFlowReflectionBake.ensureBaked(uri, cover, 1f, sourceKey)
                }
                val second = async(Dispatchers.Default) {
                    CoverFlowReflectionBake.ensureBaked(uri, cover, 1f, sourceKey)
                }
                first.await() to second.await()
            }

            assertNotNull(results.first)
            assertSame(results.first, results.second)
        } finally {
            CoverFlowReflectionBake.clear()
            cover.recycle()
        }
    }
}
