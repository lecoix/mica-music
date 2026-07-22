package com.mica.music.ui.screens.player.view

import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import com.mica.music.imaging.CoverDecodeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.AbstractList

class ArtworkWindowRetentionTest {

    private val target = CoverDecodeTarget.fromPixels(640f, 640f)

    @Test
    fun coverFlowWindow_remainsSevenKeysForTenThousandSongs() {
        val queue = fakeQueue(10_000)

        val retained = retainedArtworkKeys(
            queue = queue,
            centerIndex = 5_000,
            visibleOffsets = -3..3,
            decodeTarget = target,
        )

        assertEquals(7, retained.size)
        assertTrue(target.memoryCacheKey(queue[4_997].albumArtUri!!) in retained)
        assertTrue(target.memoryCacheKey(queue[5_003].albumArtUri!!) in retained)
        assertFalse(target.memoryCacheKey(queue[4_996].albumArtUri!!) in retained)
    }

    @Test
    fun coverFlowWindow_readsOnlySevenEntriesFromTenThousandSongList() {
        val backing = fakeQueue(10_000)
        val counted = CountingSongList(backing)

        retainedArtworkKeys(
            queue = counted,
            centerIndex = 5_000,
            visibleOffsets = -3..3,
            decodeTarget = target,
        )

        assertEquals(7, counted.readCount)
    }

    @Test
    fun photoStackWindow_retainsTransitionSongsWithoutRetainingQueue() {
        val queue = fakeQueue(10_000)
        val transitionSong = queue[42]

        val retained = retainedArtworkKeys(
            queue = queue,
            centerIndex = 5_000,
            visibleOffsets = -1..3,
            decodeTarget = target,
            extraIndices = listOf(8_000),
            extraSongs = listOf(transitionSong),
        )

        assertEquals(7, retained.size)
        assertTrue(target.memoryCacheKey(transitionSong.albumArtUri!!) in retained)
        assertTrue(target.memoryCacheKey(queue[8_000].albumArtUri!!) in retained)
        assertFalse(target.memoryCacheKey(queue.first().albumArtUri!!) in retained)
    }

    @Test
    fun maximumExplicitTransitionSet_hasConstantThirteenReferenceBound() {
        val queue = fakeQueue(10_000)

        val retained = retainedArtworkKeys(
            queue = queue,
            centerIndex = 5_000,
            visibleOffsets = -1..3,
            decodeTarget = target,
            extraIndices = listOf(7_000, 7_001, 7_002, 7_003),
            extraSongs = listOf(queue[8_000], queue[8_001], queue[8_002], queue[8_003]),
        )

        assertEquals(13, retained.size)
    }

    @Test
    fun decodeTarget_isPartOfEveryRetainedKey() {
        val queue = fakeQueue(1)
        val oldTarget = CoverDecodeTarget.fromPixels(320f, 320f)

        val retained = retainedArtworkKeys(queue, 0, 0..0, target)

        assertTrue(target.memoryCacheKey(queue[0].albumArtUri!!) in retained)
        assertFalse(oldTarget.memoryCacheKey(queue[0].albumArtUri!!) in retained)
    }

    @Test
    fun loadAcceptance_rejectsOldGenerationAfterTargetReturnsToSameSize() {
        val queue = fakeQueue(1)
        val key = target.memoryCacheKey(queue[0].albumArtUri!!)
        val retained = setOf(key)

        assertFalse(
            shouldAcceptArtworkLoad(
                requestGeneration = 1,
                activeGeneration = 3,
                requestTarget = target,
                activeTarget = target,
                bitmapKey = key,
                retainedKeys = retained,
            ),
        )
    }

    @Test
    fun loadAcceptance_rejectsKeysThatLeftTheActiveWindow() {
        val queue = fakeQueue(2)
        val staleKey = target.memoryCacheKey(queue[0].albumArtUri!!)
        val retained = setOf(target.memoryCacheKey(queue[1].albumArtUri!!))

        assertFalse(
            shouldAcceptArtworkLoad(
                requestGeneration = 2,
                activeGeneration = 2,
                requestTarget = target,
                activeTarget = target,
                bitmapKey = staleKey,
                retainedKeys = retained,
            ),
        )
    }

    @Test
    fun loadAcceptance_acceptsCurrentGenerationTargetAndWindow() {
        val queue = fakeQueue(1)
        val key = target.memoryCacheKey(queue[0].albumArtUri!!)

        assertTrue(
            shouldAcceptArtworkLoad(
                requestGeneration = 4,
                activeGeneration = 4,
                requestTarget = target,
                activeTarget = target,
                bitmapKey = key,
                retainedKeys = setOf(key),
            ),
        )
    }

    private fun fakeQueue(size: Int): List<Song> = List(size) { index ->
        Song(
            id = "song-$index",
            title = "Title $index",
            artist = "Artist",
            album = "Album",
            durationSec = 180,
            metadata = TrackMetadata(
                containerName = "FLAC",
                sampleRateHz = 44_100,
                bitsPerSample = 16,
                bitrateKbps = 1_000,
                channelCount = 2,
                playbackMimeType = "audio/flac",
            ),
            albumArtUri = "content://fake/$index",
            coverColorArgb = 0xFF112233.toInt(),
            mediaUri = "content://media/$index",
        )
    }

    private class CountingSongList(
        private val backing: List<Song>,
    ) : AbstractList<Song>() {
        var readCount: Int = 0
            private set

        override val size: Int
            get() = backing.size

        override fun get(index: Int): Song {
            readCount++
            return backing[index]
        }
    }
}
