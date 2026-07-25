package com.mica.music.media

import com.mica.music.data.LyricsDocument
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarBluetoothLyricsPayloadTest {
    @Test
    fun lyricUsesLineAsTitleAndKeepsCanonicalSongInArtist() {
        val payload = CarBluetoothLyricsPayload.lyric(song(), "  current lyric  ")

        assertEquals("current lyric", payload?.title)
        assertEquals("Song - Artist", payload?.artist)
        assertEquals("Album", payload?.album)
        assertEquals(123_000L, payload?.durationMs)
    }

    @Test
    fun blankLyricIsIgnored() {
        assertNull(CarBluetoothLyricsPayload.lyric(song(), "  "))
    }

    @Test
    fun defaultPayloadRestoresSongTitle() {
        assertEquals("Song", CarBluetoothLyricsPayload.default(song()).title)
    }

    private fun song() = Song(
        id = "song-1",
        title = "Song",
        artist = "Artist",
        album = "Album",
        durationSec = 123,
        metadata = TrackMetadata.fallback("audio/flac", 0),
        albumArtUri = null,
        coverColorArgb = 0,
        mediaUri = "file:///song.flac",
        lyricsDocument = LyricsDocument(),
    )
}
