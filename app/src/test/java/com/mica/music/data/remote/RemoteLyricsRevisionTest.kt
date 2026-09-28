package com.mica.music.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RemoteLyricsRevisionTest {
    @Test
    fun `sidecar revision changes invalidate lyrics fingerprint`() {
        val first = remoteTrackLyricsRevision(
            fileName = "Song.flac",
            resourceId = "Album/Song.flac",
            contentRevision = "audio-v1",
            sizeBytes = 1000,
            candidates = listOf(
                RemoteLyricsSidecarCandidate("Song.lrc", "Album/Song.lrc", "lyrics-v1", 100),
            ),
        )
        val changed = remoteTrackLyricsRevision(
            fileName = "Song.flac",
            resourceId = "Album/Song.flac",
            contentRevision = "audio-v1",
            sizeBytes = 1000,
            candidates = listOf(
                RemoteLyricsSidecarCandidate("Song.lrc", "Album/Song.lrc", "lyrics-v2", 101),
            ),
        )

        assertNotEquals(first, changed)
    }

    @Test
    fun `without sidecars embedded fingerprint follows audio revision`() {
        val first = remoteTrackLyricsRevision("Song.flac", "Album/Song.flac", "audio-v1", 1000, emptyList())
        val same = remoteTrackLyricsRevision("Song.flac", "Album/Song.flac", "audio-v1", 1000, emptyList())
        val changed = remoteTrackLyricsRevision("Song.flac", "Album/Song.flac", "audio-v2", 1000, emptyList())

        assertEquals(first, same)
        assertNotEquals(first, changed)
    }
}
