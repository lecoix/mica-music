package com.mica.music.imaging

import coil.size.Scale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StandardCoverRequestSpecTest {

    @Test
    fun `standard request keeps exact requested pixel dimensions`() {
        val spec = StandardCoverRequestSpec.fromPixels(1220f, 1067.6f, Scale.FIT)

        assertEquals(1220, spec.widthPx)
        assertEquals(1068, spec.heightPx)
    }

    @Test
    fun `cache key distinguishes fit from fill without changing uri`() {
        val fit = StandardCoverRequestSpec.fromPixels(1220f, 1220f, Scale.FIT)
        val fill = StandardCoverRequestSpec.fromPixels(1220f, 1220f, Scale.FILL)

        assertNotEquals(
            fit.memoryCacheKey("content://album/42"),
            fill.memoryCacheKey("content://album/42"),
        )
        assertEquals(
            "cover:standard:1220x1220:fit:content://album/42",
            fit.memoryCacheKey("content://album/42"),
        )
    }
}
