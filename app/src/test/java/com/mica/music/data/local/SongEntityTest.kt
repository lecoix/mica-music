package com.mica.music.data.local

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SongEntityTest {

    @Test
    fun songRoundTripPreservesPersistedFieldsAndLyrics() {
        val song = SongFixtures.song(id = "round-trip", queueOrder = 4)
        val entity = song.toEntity(queueOrder = 7)
        val restored = entity.toSong()

        assertEquals(song.copy(lastPlayedAtMs = 0), restored)
        assertEquals(7, entity.queueOrder)
    }

    @Test
    fun corruptLyricsJsonFallsBackToEmptyList() {
        val entity = SongFixtures.song().toEntity(0).copy(lyricsJson = "{broken")
        assertTrue(entity.toSong().lyrics.isEmpty())
    }
}
