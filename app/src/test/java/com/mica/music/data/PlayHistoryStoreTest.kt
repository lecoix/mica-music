package com.mica.music.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayHistoryStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("mica_play_counts", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("mica_play_counts", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun listenSecondsAccumulateWithoutChangingPlayCount() {
        PlayHistoryStore.recordPlay(context, "song-1")
        val first = PlayHistoryStore.recordListenSeconds(context, "song-1", 65)
        val second = PlayHistoryStore.recordListenSeconds(context, "song-1", 125)

        assertEquals(1, first.count)
        assertEquals(65L, first.totalListenSeconds)
        assertEquals(1, second.count)
        assertEquals(190L, second.totalListenSeconds)
        assertEquals(first.lastPlayedAtMs, second.lastPlayedAtMs)
    }

    @Test
    fun migrateSongIdsPreservesStatsAndRecentOrder() {
        PlayHistoryStore.recordPlay(context, "legacy")
        PlayHistoryStore.recordListenSeconds(context, "legacy", 42)

        PlayHistoryStore.migrateSongIds(context, mapOf("legacy" to "doc_sha256_new"))

        assertEquals(1, PlayHistoryStore.getStats(context, "doc_sha256_new").count)
        assertEquals(42L, PlayHistoryStore.getStats(context, "doc_sha256_new").totalListenSeconds)
        assertEquals(listOf("doc_sha256_new"), PlayHistoryStore.recentSongIds(context))
        assertEquals(0, PlayHistoryStore.getStats(context, "legacy").count)
    }

    @Test
    fun statsSnapshotCapturesRequestedSongsWithoutChangingSingleSongReads() {
        PlayHistoryStore.recordPlay(context, "song-1")
        PlayHistoryStore.recordListenSeconds(context, "song-1", 42)

        val snapshot = PlayHistoryStore.snapshotStats(context, listOf("song-1", "missing"))

        assertEquals(PlayHistoryStore.getStats(context, "song-1"), snapshot["song-1"])
        assertEquals(PlayStats(0, 0L, 0L), snapshot["missing"])

        PlayHistoryStore.recordListenSeconds(context, "song-1", 8)
        assertEquals(42L, snapshot["song-1"].totalListenSeconds)
        assertEquals(50L, PlayHistoryStore.getStats(context, "song-1").totalListenSeconds)
    }
}
