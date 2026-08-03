package com.mica.music.data.scanner

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoCoverPosterPrefetcherTest {

    @Test
    fun prefetchSkipsCachedDedupesAndStoresExtracted() {
        val cached = setOf("content://cached")
        val extracted = mutableListOf<String>()
        val stored = mutableListOf<String>()
        val stats = VideoCoverPosterPrefetcher.prefetchVideoCoverPosters(
            uris = listOf(
                "content://cached",
                "content://fresh",
                "content://fresh",
                "",
                "content://fail",
            ),
            isCached = { it in cached },
            extract = { uri ->
                extracted += uri
                if (uri.endsWith("fail")) null
                else Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            },
            store = { uri, _ -> stored += uri },
        )
        assertEquals(listOf("content://fresh", "content://fail"), extracted)
        assertEquals(listOf("content://fresh"), stored)
        assertEquals(
            VideoCoverPosterPrefetcher.PrefetchStats(
                total = 3,
                skipped = 1,
                stored = 1,
                failed = 1,
            ),
            stats,
        )
    }

    @Test
    fun prefetchStopsWhenCancelled() {
        var calls = 0
        var go = true
        VideoCoverPosterPrefetcher.prefetchVideoCoverPosters(
            uris = listOf("content://a", "content://b", "content://c"),
            isCached = { false },
            extract = {
                calls++
                go = false
                Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            },
            store = { _, _ -> },
            shouldContinue = { go },
        )
        assertEquals(1, calls)
    }

    @Test
    fun prefetchDoesNotStoreWhenCancelledDuringExtraction() {
        var go = true
        val stored = mutableListOf<String>()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

        try {
            val stats = VideoCoverPosterPrefetcher.prefetchVideoCoverPosters(
                uris = listOf("content://cancel-during-extract"),
                isCached = { false },
                extract = {
                    go = false
                    bitmap
                },
                store = { uri, _ -> stored += uri },
                shouldContinue = { go },
            )

            assertEquals(0, stats.stored)
            assertEquals(emptyList<String>(), stored)
            assertTrue(bitmap.isRecycled)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}
