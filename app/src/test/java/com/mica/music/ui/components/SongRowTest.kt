package com.mica.music.ui.components

import com.mica.music.data.Song
import com.mica.music.data.SongListInfoVisibility
import com.mica.music.data.TrackMetadata
import com.mica.music.data.SongTrailingInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class SongRowTest {
    @Test
    fun trailingInfoSupportsPlayCountFormatDurationAndNone() {
        assertEquals("2", songTrailingLabel(song, SongTrailingInfo.PLAY_COUNT))
        assertEquals(song.metadata.formatLabel, songTrailingLabel(song, SongTrailingInfo.FORMAT))
        assertEquals(song.durationLabel, songTrailingLabel(song, SongTrailingInfo.DURATION))
        assertEquals(null, songTrailingLabel(song, SongTrailingInfo.NONE))
    }
    private val song = Song(
        id = "song",
        title = "Title",
        artist = "Artist",
        album = "Album",
        durationSec = 225,
        metadata = TrackMetadata("FLAC", 44_100, 16, 900, 2, "audio/flac"),
        albumArtUri = null,
        coverColorArgb = 0,
        mediaUri = "content://song",
        playCount = 2,
    )

    @Test
    fun subtitleIncludesEnabledDuration() {
        val visibility = SongListInfoVisibility(
            showSongArtist = false,
            showSongAlbum = false,
            showSongPlayCount = false,
            showSongDuration = true,
        )

        assertEquals("3:45", songSubtitle(song, visibility))
    }

    @Test
    fun subtitleKeepsExistingDefaults() {
        assertEquals("Artist · Album · 2 次播放", songSubtitle(song, SongListInfoVisibility()))
    }
}
