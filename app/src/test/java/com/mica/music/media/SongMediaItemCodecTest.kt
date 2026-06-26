package com.mica.music.media

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
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
        )

        val decoded = SongMediaItemCodec.decode(SongMediaItemCodec.encode(song))

        assertEquals(song.copy(lyrics = emptyList()), decoded)
    }

    @Test
    fun metadataOnlyItemStillDecodesForSoftwarePlayback() {
        val song = SongFixtures.song(id = "alac", container = "ALAC", mime = "audio/alac")
        val item = SongMediaItemCodec.encode(song, includeUri = false)

        assertNull(item.localConfiguration)
        assertEquals(song.id, SongMediaItemCodec.decode(item)?.id)
    }
}
