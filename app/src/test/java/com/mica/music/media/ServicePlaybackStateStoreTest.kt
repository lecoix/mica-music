package com.mica.music.media

import android.content.Context
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServicePlaybackStateStoreTest {
    private lateinit var context: Context
    private lateinit var store: ServicePlaybackStateStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = ServicePlaybackStateStore(context)
        store.clear(sync = true)
    }

    @Test
    fun roundTripsCompleteServiceSnapshot() {
        val snapshot = ServicePlaybackSnapshot(
            queueSongIds = listOf("one", "two", "three"),
            currentIndex = 1,
            positionMs = 12_345L,
            repeatMode = Player.REPEAT_MODE_ALL,
            shuffleEnabled = true,
            playWhenReady = true,
            qualityMode = AudioQualityMode.DSP,
        )

        store.save(snapshot, sync = true)

        assertEquals(snapshot, store.load())
    }

    @Test
    fun resolvesCurrentSongByIdWhenQueueOrderChanges() {
        val snapshot = ServicePlaybackSnapshot(
            queueSongIds = listOf("one", "two", "three"),
            currentIndex = 1,
            positionMs = 9_000L,
            repeatMode = Player.REPEAT_MODE_ONE,
            shuffleEnabled = false,
            playWhenReady = true,
            qualityMode = AudioQualityMode.HIFI,
        )

        val restore = ServicePlaybackRestoreResolver.resolve(
            snapshot = snapshot,
            availableSongIds = listOf("three", "one", "two"),
        )

        assertEquals(
            ServicePlaybackRestore(
                currentIndex = 2,
                positionMs = 9_000L,
                repeatMode = Player.REPEAT_MODE_ONE,
                shuffleEnabled = false,
            ),
            restore,
        )
    }

    @Test
    fun missingSavedSongDoesNotRestoreAnotherTrack() {
        val snapshot = ServicePlaybackSnapshot(
            queueSongIds = listOf("missing"),
            currentIndex = 0,
            positionMs = 9_000L,
            repeatMode = Player.REPEAT_MODE_OFF,
            shuffleEnabled = false,
            playWhenReady = false,
            qualityMode = AudioQualityMode.HIFI,
        )

        assertNull(
            ServicePlaybackRestoreResolver.resolve(
                snapshot = snapshot,
                availableSongIds = listOf("one", "two"),
            ),
        )
    }

    @Test
    fun invalidRepeatModeFallsBackToOff() {
        val snapshot = ServicePlaybackSnapshot(
            queueSongIds = listOf("one"),
            currentIndex = 0,
            positionMs = -1L,
            repeatMode = 999,
            shuffleEnabled = true,
            playWhenReady = false,
            qualityMode = AudioQualityMode.HIFI,
        )

        assertEquals(
            ServicePlaybackRestore(
                currentIndex = 0,
                positionMs = 0L,
                repeatMode = Player.REPEAT_MODE_OFF,
                shuffleEnabled = true,
            ),
            ServicePlaybackRestoreResolver.resolve(snapshot, listOf("one")),
        )
    }

    @Test
    fun migratesLegacySongAndPositionIntoServiceRestore() {
        context.getSharedPreferences("mica_playback_session", Context.MODE_PRIVATE)
            .edit()
            .putString("song_id", "legacy")
            .putInt("position_ms", 7_654)
            .commit()

        val snapshot = store.load()

        assertEquals(listOf("legacy"), snapshot?.queueSongIds)
        assertEquals(7_654L, snapshot?.positionMs)
        assertEquals(false, snapshot?.playWhenReady)
    }

    @Test
    fun tornQueueAndCursorRevisionsRecoverByCurrentSongId() {
        store.saveQueue(
            ServiceQueueSnapshot(listOf("one", "two", "three"), revision = 2L),
            sync = true,
        )
        store.saveCursor(
            ServicePlaybackCursor(
                currentSongId = "two",
                positionMs = 4_321L,
                repeatMode = Player.REPEAT_MODE_OFF,
                shuffleEnabled = false,
                playWhenReady = true,
                qualityMode = AudioQualityMode.HIFI,
                queueRevision = 1L,
            ),
            sync = true,
        )

        val snapshot = store.load()

        assertEquals("two", snapshot?.currentSongId)
        assertEquals(1, snapshot?.currentIndex)
        assertEquals(4_321L, snapshot?.positionMs)
    }
}
