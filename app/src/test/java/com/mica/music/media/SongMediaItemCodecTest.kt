package com.mica.music.media

import com.mica.music.data.ReplayGainTags
import com.mica.music.data.SongSource
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SongMediaItemCodecTest {
    @Test
    fun roundTripPreservesSoftwareDecodeFields() {
        val song = SongFixtures.song(
            id = "dsd",
            container = "DSD",
            mime = "audio/dsf",
            totalListenSeconds = 3_661L,
        ).copy(
            discNumber = 2,
            replayGain = ReplayGainTags(-6f, 0.9f, -4f, 0.8f),
            videoCoverUri = "content://library/Album.mp4",
            musicVideoUri = "content://library/dsd.mp4",
            musicVideoRevision = "content://library/dsd.mp4|123|456",
        )

        val decoded = SongMediaItemCodec.decode(SongMediaItemCodec.encode(song))

        assertEquals(
            song.copy(
                lyricsDocument = com.mica.music.data.LyricsDocument(),
                lyricsLoaded = false,
            ),
            decoded,
        )
    }

    @Test
    fun metadataOnlyItemStillDecodesForSoftwarePlayback() {
        val song = SongFixtures.song(id = "alac", container = "ALAC", mime = "audio/alac")
        val item = SongMediaItemCodec.encode(song, includeUri = false)

        assertNull(item.localConfiguration)
        assertEquals(song.id, SongMediaItemCodec.decode(item)?.id)
    }

    @Test
    fun roundTripPreservesTransientSourceBoundary() {
        val song = SongFixtures.song(id = "external").copy(source = SongSource.TRANSIENT_EXTERNAL)

        assertEquals(song.source, SongMediaItemCodec.decode(SongMediaItemCodec.encode(song))?.source)
    }

    @Test
    fun metadataRevisionExcludesPlaybackStatsButIncludesLyricsAndStaticMetadata() {
        val song = SongFixtures.song("revision")
        val revision = SongMediaItemCodec.metadataRevision(song)

        assertEquals(
            revision,
            SongMediaItemCodec.metadataRevision(
                song.copy(playCount = 99, totalListenSeconds = 1234, lastPlayedAtMs = 5678),
            ),
        )
        assertEquals(revision, SongMediaItemCodec.metadataRevision(song.copy(metadataScanVersion = 0)))
        assertNotEquals(revision, SongMediaItemCodec.metadataRevision(song.copy(title = "updated")))
        assertNotEquals(revision, SongMediaItemCodec.metadataRevision(song.copy(releaseDate = "2024-02-29")))
        assertNotEquals(
            revision,
            SongMediaItemCodec.metadataRevision(
                song.copy(
                    musicVideoUri = "content://library/revision.mp4",
                    musicVideoRevision = "content://library/revision.mp4|1|2",
                ),
            ),
        )
        assertNotEquals(
            revision,
            SongMediaItemCodec.metadataRevision(song.copy(lyricsDocument = com.mica.music.data.LyricsDocument())),
        )
    }
}
