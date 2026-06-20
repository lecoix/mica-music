package com.mica.music.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CoverDecodeTargetTest {

    @Test
    fun `special theme target keeps full screen size for immersive expansion`() {
        val target = CoverDecodeTarget.forSpecialTheme(screenWidthPx = 1080f)

        assertEquals(1088, target.widthPx)
        assertEquals(1088, target.heightPx)
    }

    @Test
    fun `nearby animated sizes share a stable bucket and cache key`() {
        val first = CoverDecodeTarget.forSpecialTheme(screenWidthPx = 1072f)
        val second = CoverDecodeTarget.forSpecialTheme(screenWidthPx = 1080f)

        assertEquals(first, second)
        assertEquals(
            first.memoryCacheKey("content://album/42"),
            second.memoryCacheKey("content://album/42"),
        )
    }

    @Test
    fun `different decode sizes cannot alias in memory cache`() {
        val compact = CoverDecodeTarget.fromPixels(512f, 512f)
        val player = CoverDecodeTarget.fromPixels(1080f, 1080f)

        assertNotEquals(
            compact.memoryCacheKey("content://album/42"),
            player.memoryCacheKey("content://album/42"),
        )
    }
}
