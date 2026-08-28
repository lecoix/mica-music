package com.mica.music.data.scanner

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicVideoMatcherTest {
    @Test
    fun exactBaseNameInSameFolderCreatesRevisionedPair() {
        val song = SongFixtures.song("Track").copy(folderPath = "Artist/Album")
        val video = VideoCoverFile(
            uri = "content://library/Track.mp4",
            folderPath = "Artist/Album",
            baseName = "Track",
            sizeBytes = 123L,
            lastModifiedMs = 456L,
        )

        val matched = MusicVideoMatcher.attach(listOf(song), listOf(video)).single()

        assertEquals(video.uri, matched.musicVideoUri)
        assertEquals("${video.uri}|123|456", matched.musicVideoRevision)
    }

    @Test
    fun normalizedMatchUsesNfkcWhitespaceAndRootCase() {
        val song = SongFixtures.song("one").copy(
            fileName = "ＡＢ  Song.flac",
            folderPath = "Album",
        )
        val video = VideoCoverFile("content://video", "Album", "ab song", 1L, 2L)

        assertEquals(video.uri, MusicVideoMatcher.attach(listOf(song), listOf(video)).single().musicVideoUri)
    }

    @Test
    fun exactMatchWinsBeforeNormalizedAmbiguity() {
        val song = SongFixtures.song("one").copy(fileName = "Song.flac", folderPath = "Album")
        val videos = listOf(
            VideoCoverFile("content://exact", "Album", "Song"),
            VideoCoverFile("content://normalized", "Album", " song "),
        )

        assertEquals("content://exact", MusicVideoMatcher.attach(listOf(song), videos).single().musicVideoUri)
    }

    @Test
    fun crossDirectoryAndSuffixNamesDoNotMatch() {
        val song = SongFixtures.song("one").copy(fileName = "Song.flac", folderPath = "Wanted")
        val videos = listOf(
            VideoCoverFile("content://other", "Other", "Song"),
            VideoCoverFile("content://suffix", "Wanted", "Song (Official Video)"),
        )

        assertNull(MusicVideoMatcher.attach(listOf(song), videos).single().musicVideoUri)
    }

    @Test
    fun multipleAudioFormatsOrVideosAreAmbiguous() {
        val songs = listOf(
            SongFixtures.song("flac").copy(fileName = "Song.flac", folderPath = "Album"),
            SongFixtures.song("mp3").copy(fileName = "Song.mp3", folderPath = "Album"),
        )
        val video = VideoCoverFile("content://one", "Album", "Song")
        val oneSong = songs.first()
        val duplicateVideos = listOf(video, video.copy(uri = "content://two"))

        assertEquals(listOf(null, null), MusicVideoMatcher.attach(songs, listOf(video)).map { it.musicVideoUri })
        assertNull(MusicVideoMatcher.attach(listOf(oneSong), duplicateVideos).single().musicVideoUri)
    }

    @Test
    fun noMatchClearsStalePairing() {
        val stale = SongFixtures.song("one").copy(
            musicVideoUri = "content://stale",
            musicVideoRevision = "stale",
        )

        val result = MusicVideoMatcher.attach(listOf(stale), emptyList()).single()

        assertNull(result.musicVideoUri)
        assertEquals("", result.musicVideoRevision)
    }

    @Test(timeout = 5_000L)
    fun tenThousandSongsAndVideosStayLinearAndUnderAllocationBudget() {
        val songs = List(10_000) { index ->
            SongFixtures.song("song-$index").copy(
                fileName = "Track $index.flac",
                folderPath = "Library/${index / 20}",
            )
        }
        val videos = List(10_000) { index ->
            VideoCoverFile(
                uri = "content://video/$index",
                folderPath = "Library/${index / 20}",
                baseName = "Track $index",
                sizeBytes = index.toLong(),
                lastModifiedMs = index.toLong() + 1,
            )
        }
        val runtime = Runtime.getRuntime()
        System.gc()
        val before = runtime.totalMemory() - runtime.freeMemory()

        val matched = MusicVideoMatcher.attach(songs, videos)

        val retainedHeapDelta = (runtime.totalMemory() - runtime.freeMemory() - before).coerceAtLeast(0L)
        assertEquals(10_000, matched.count { it.musicVideoUri != null })
        assertTrue(
            "matcher retained heap delta was $retainedHeapDelta bytes",
            retainedHeapDelta <= 16L * 1024L * 1024L,
        )
    }
}
