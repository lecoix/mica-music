package com.mica.music.media

import android.content.Context
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.PlaybackTuning
import com.mica.music.data.SongSource
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val externalSong = ServiceExternalSongSnapshot.from(
            SongFixtures.song("external_test").copy(source = SongSource.TRANSIENT_EXTERNAL),
        )
        val snapshot = ServicePlaybackSnapshot(
            queueSongIds = listOf("one", "two", "three"),
            currentIndex = 1,
            positionMs = 12_345L,
            repeatMode = Player.REPEAT_MODE_ALL,
            shuffleEnabled = true,
            playWhenReady = true,
            qualityMode = AudioQualityMode.DSP,
            playbackTuning = PlaybackTuning(speed = 1.25f, pitchSemitones = 7f),
            externalSongs = listOf(externalSong),
        )

        store.save(snapshot, sync = true)

        assertEquals(snapshot, store.load())
    }

    @Test
    fun externalSnapshotRecreatesTransientSongWithoutLyricsOrLibraryStats() {
        val original = SongFixtures.song("external_test", totalListenSeconds = 123L).copy(
            source = SongSource.TRANSIENT_EXTERNAL,
            playCount = 4,
        )

        val restored = ServiceExternalSongSnapshot.from(original).toSong()

        assertEquals(original.id, restored.id)
        assertEquals(original.mediaUri, restored.mediaUri)
        assertEquals(original.metadata, restored.metadata)
        assertEquals(SongSource.TRANSIENT_EXTERNAL, restored.source)
        assertFalse(restored.lyricsLoaded)
        assertTrue(restored.lyricsDocument.lines.isEmpty())
        assertEquals(0, restored.playCount)
        assertEquals(0L, restored.totalListenSeconds)
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
