package com.mica.music.media

import com.mica.music.data.playback.ServicePlaybackSnapshot

import com.mica.music.audio.AudioQualityMode

import androidx.media3.common.Player
import com.mica.music.data.PlaybackTuning
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ServicePlaybackBootstrapResolverTest {
    @Test
    fun persistedQueueIsMaterializedInSavedOrderWithSavedCursor() {
        val a = SongFixtures.song("a")
        val b = SongFixtures.song("b")
        val c = SongFixtures.song("c")
        val tuning = PlaybackTuning(speed = 1.25f, pitchSemitones = 2f)
        val snapshot = ServicePlaybackSnapshot(
            queueSongIds = listOf("a", "b", "c"),
            currentIndex = 1,
            positionMs = 42_345L,
            repeatMode = Player.REPEAT_MODE_ONE,
            shuffleEnabled = false,
            playWhenReady = true,
            qualityMode = AudioQualityMode.HIFI,
            playbackTuning = tuning,
            currentSongId = "b",
        )

        val bootstrap = ServicePlaybackBootstrapResolver.resolve(
            snapshot,
            mapOf("c" to c, "a" to a, "b" to b),
        )

        assertNotNull(bootstrap)
        assertEquals(listOf("a", "b", "c"), bootstrap!!.songs.map { it.id })
        assertEquals(1, bootstrap.currentIndex)
        assertEquals(42_345L, bootstrap.positionMs)
        assertEquals(Player.REPEAT_MODE_ONE, bootstrap.repeatMode)
        assertEquals(tuning, bootstrap.playbackTuning)
    }

    @Test
    fun missingNonCurrentSongsDoNotShiftCurrentSongIdentity() {
        val b = SongFixtures.song("b")
        val c = SongFixtures.song("c")
        val snapshot = ServicePlaybackSnapshot(
            queueSongIds = listOf("missing", "b", "c"),
            currentIndex = 1,
            positionMs = 9_000L,
            repeatMode = 999,
            shuffleEnabled = false,
            playWhenReady = true,
            qualityMode = AudioQualityMode.HIFI,
            currentSongId = "b",
        )

        val bootstrap = ServicePlaybackBootstrapResolver.resolve(snapshot, mapOf("b" to b, "c" to c))

        assertNotNull(bootstrap)
        assertEquals(listOf("b", "c"), bootstrap!!.songs.map { it.id })
        assertEquals(0, bootstrap.currentIndex)
        assertEquals(Player.REPEAT_MODE_OFF, bootstrap.repeatMode)
    }

    @Test
    fun emptyResolutionDoesNotInventAQueue() {
        val snapshot = ServicePlaybackSnapshot(
            queueSongIds = listOf("missing"),
            currentIndex = 0,
            positionMs = 1_000L,
            repeatMode = Player.REPEAT_MODE_OFF,
            shuffleEnabled = false,
            playWhenReady = false,
            qualityMode = AudioQualityMode.HIFI,
        )

        assertNull(ServicePlaybackBootstrapResolver.resolve(snapshot, emptyMap()))
    }
}
