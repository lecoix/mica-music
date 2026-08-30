package com.mica.music.data.remote

import com.mica.music.data.PlayStats
import com.mica.music.data.PlayStatsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePlayStatsPresentationTest {
    @Test
    fun catalogHydrationPublishesPersistedStatsAndPrunesMissingIds() {
        val firstId = remoteId("source", "first")
        val secondId = remoteId("source", "second")
        val presentation = RemotePlayStatsPresentation()

        presentation.publishCatalog(
            mediaIds = listOf(firstId, secondId),
            persisted = snapshot(
                firstId to PlayStats(count = 2, lastPlayedAtMs = 20L, totalListenSeconds = 90L),
            ),
        )
        assertEquals(2, presentation.stats.value[firstId]?.count)
        assertFalse(presentation.stats.value.containsKey(secondId))

        presentation.publishCatalog(
            mediaIds = listOf(secondId),
            persisted = snapshot(
                secondId to PlayStats(count = 1, lastPlayedAtMs = 30L, totalListenSeconds = 10L),
            ),
        )

        assertFalse(presentation.stats.value.containsKey(firstId))
        assertEquals(1, presentation.stats.value[secondId]?.count)
    }

    @Test
    fun lateHydrationCannotRollBackNewerLiveRemoteStats() {
        val mediaId = remoteId("source", "track")
        val presentation = RemotePlayStatsPresentation()

        presentation.applyLive(
            mediaId,
            PlayStats(count = 4, lastPlayedAtMs = 400L, totalListenSeconds = 120L),
        )
        presentation.publishCatalog(
            mediaIds = listOf(mediaId),
            persisted = snapshot(
                mediaId to PlayStats(count = 3, lastPlayedAtMs = 300L, totalListenSeconds = 100L),
            ),
        )

        assertEquals(
            PlayStats(count = 4, lastPlayedAtMs = 400L, totalListenSeconds = 120L),
            presentation.stats.value[mediaId],
        )
    }

    @Test
    fun liveUpdatesIgnoreLocalOrMalformedIds() {
        val presentation = RemotePlayStatsPresentation()
        presentation.applyLive("local-song", PlayStats(1, 1L, 1L))
        presentation.applyLive("mica.remote.v1.not-valid", PlayStats(1, 1L, 1L))

        assertTrue(presentation.stats.value.isEmpty())
    }

    @Test
    fun mergePlayStatsUsesMonotonicFieldsIndependently() {
        assertEquals(
            PlayStats(count = 5, lastPlayedAtMs = 900L, totalListenSeconds = 300L),
            mergePlayStats(
                PlayStats(count = 5, lastPlayedAtMs = 700L, totalListenSeconds = 200L),
                PlayStats(count = 4, lastPlayedAtMs = 900L, totalListenSeconds = 300L),
            ),
        )
    }

    private fun remoteId(source: String, track: String): String =
        RemoteMediaIdCodec.encode(RemoteTrackRef(source, track))

    private fun snapshot(vararg entries: Pair<String, PlayStats>): PlayStatsSnapshot =
        PlayStatsSnapshot.from(mapOf(*entries))
}
