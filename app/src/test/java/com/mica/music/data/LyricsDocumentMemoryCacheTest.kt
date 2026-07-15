package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LyricsDocumentMemoryCacheTest {

    @Test
    fun evictsLeastRecentlyUsedDocumentWhenByteBudgetIsExceeded() {
        val document = LyricsDocument(
            lines = listOf(
                LyricLineNode(
                    id = "line",
                    startMs = 0,
                    parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "x".repeat(200))),
                ),
            ),
        )
        val cache = LyricsDocumentMemoryCache(maxBytes = 1_000)
        val first = LyricsCacheKey("first", "1", 1)
        val second = LyricsCacheKey("second", "1", 1)

        cache.put(first, document)
        cache.put(second, document)

        assertNull(cache.get(first))
        assertEquals(document, cache.get(second))
        assertEquals(1, cache.entryCount())
    }
}
