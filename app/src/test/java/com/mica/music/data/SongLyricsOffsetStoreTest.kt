package com.mica.music.data

import com.mica.music.data.local.SongLyricsOffsetEntity
import com.mica.music.data.local.SongLyricsOffsetDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SongLyricsOffsetStoreTest {
    @Test
    fun newerRequestWinsWhenOlderDatabaseWriteCompletesLate() = runTest {
        val oldWriteStarted = CompletableDeferred<Unit>()
        val releaseOldWrite = CompletableDeferred<Unit>()
        val dao = BlockingOffsetDao(oldWriteStarted, releaseOldWrite)
        val store = SongLyricsOffsetStore(dao, StandardTestDispatcher(testScheduler))
        val song = testSong(id = "song", mediaUri = "content://song")

        store.requestSet(song, 500)
        runCurrent()
        oldWriteStarted.await()

        store.requestSet(song, -300)
        releaseOldWrite.complete(Unit)
        advanceUntilIdle()

        assertEquals(-300, dao.value.value?.offsetMs)
    }

    @Test
    fun persistedOffsetRequiresBothStableSongIdAndMediaUri() {
        val song = testSong(id = "ms_42", mediaUri = "content://media/42")
        val stored = SongLyricsOffsetEntity(song.id, song.mediaUri, 500)

        assertEquals(500, SongLyricsOffsetStore.validOffset(stored, song))
        assertEquals(0, SongLyricsOffsetStore.validOffset(stored, song.copy(mediaUri = "content://media/reused")))
    }

    @Test
    fun storedOffsetIsClampedOnRead() {
        val song = testSong(id = "song", mediaUri = "content://song")
        val stored = SongLyricsOffsetEntity(song.id, song.mediaUri, 99_000)

        assertEquals(MAX_LYRICS_OFFSET_MS, SongLyricsOffsetStore.validOffset(stored, song))
    }

    private fun testSong(id: String, mediaUri: String) = Song(
        id = id,
        title = "Title",
        artist = "Artist",
        album = "Album",
        durationSec = 60,
        metadata = TrackMetadata.fallback("audio/mpeg", 320_000),
        albumArtUri = null,
        coverColorArgb = 0,
        mediaUri = mediaUri,
    )

    private class BlockingOffsetDao(
        private val oldWriteStarted: CompletableDeferred<Unit>,
        private val releaseOldWrite: CompletableDeferred<Unit>,
    ) : SongLyricsOffsetDao {
        val value = MutableStateFlow<SongLyricsOffsetEntity?>(null)
        private var writeCount = 0

        override fun observe(songId: String): Flow<SongLyricsOffsetEntity?> = value

        override suspend fun get(songId: String): SongLyricsOffsetEntity? = value.value

        override suspend fun upsert(entity: SongLyricsOffsetEntity) {
            writeCount += 1
            if (writeCount == 1) {
                oldWriteStarted.complete(Unit)
                releaseOldWrite.await()
            }
            value.value = entity
        }

        override suspend fun delete(songId: String) {
            value.value = null
        }

    }
}
