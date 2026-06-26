package com.mica.music.data.local

import com.mica.music.testutil.SongFixtures
import com.mica.music.data.LyricCue
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

        assertEquals(song.copy(totalListenSeconds = 0L, lastPlayedAtMs = 0), restored)
        assertEquals(7, entity.queueOrder)
    }

    @Test
    fun corruptLyricsJsonFallsBackToEmptyList() {
        val entity = SongFixtures.song().toEntity(0).copy(lyricsJson = "{broken")
        assertTrue(entity.toSong().lyrics.isEmpty())
    }

    @Test
    fun songRoundTripPreservesWordCuesAndReadsLegacyJson() {
        val song = SongFixtures.song().copy(
            lyrics = listOf(
                com.mica.music.data.LyricLine(
                    1_000,
                    "hello world",
                    listOf(LyricCue(1_000, "hello "), LyricCue(1_500, "world")),
                ),
            ),
        )
        assertEquals(song.lyrics, song.toEntity(0).toSong().lyrics)

        val legacy = song.toEntity(0).copy(lyricsJson = "[{\"t\":1000,\"x\":\"legacy\"}]")
        assertEquals("legacy", legacy.toSong().lyrics.single().text)
        assertTrue(legacy.toSong().lyrics.single().cues.isEmpty())
    }
}
