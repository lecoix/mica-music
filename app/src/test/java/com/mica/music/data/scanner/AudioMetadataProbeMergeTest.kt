package com.mica.music.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioMetadataProbeMergeTest {
    @Test
    fun partialTagLibResultIsCompletedFieldByField() {
        val tagLib = TagInfo(
            title = "TagLib title",
            artist = "",
            album = "",
            albumArtist = "TagLib album artist",
            copyright = "",
            durationSec = 120,
            year = 0,
            trackNumber = 7,
            discNumber = 2,
        )
        val retriever = TagInfo(
            title = "Retriever title",
            artist = "WAV artist",
            album = "WAV album",
            albumArtist = "Retriever album artist",
            copyright = "WAV copyright",
            durationSec = 121,
            year = 2024,
            releaseDate = "2024-02-29",
            trackNumber = 9,
            discNumber = 3,
        )

        val merged = mergeTagInfo(tagLib, retriever)

        assertEquals("TagLib title", merged.title)
        assertEquals("WAV artist", merged.artist)
        assertEquals("WAV album", merged.album)
        assertEquals("TagLib album artist", merged.albumArtist)
        assertEquals("WAV copyright", merged.copyright)
        assertEquals(120, merged.durationSec)
        assertEquals(2024, merged.year)
        assertEquals("2024-02-29", merged.releaseDate)
        assertEquals(7, merged.trackNumber)
        assertEquals(2, merged.discNumber)
    }

    @Test
    fun wavFallbackRunsBeforeRetrieverDefaults() {
        val tagLib = TagInfo(
            title = "TagLib title",
            artist = "",
            album = "",
            albumArtist = "",
            copyright = "",
            durationSec = 120,
            year = 0,
        )
        val jAudioTagger = TagInfo(
            title = "ID3 title",
            artist = "ID3 artist",
            album = "ID3 album",
            albumArtist = "",
            copyright = "",
            durationSec = 120,
            year = 2001,
        )
        val retrieverDefaults = TagInfo(
            title = "file-name",
            artist = "unknown artist",
            album = "unknown album",
            albumArtist = "Retriever album artist",
            copyright = "",
            durationSec = 121,
            year = 0,
            trackNumber = 11,
            discNumber = 4,
        )

        val withWavFallback = mergeTagInfo(tagLib, jAudioTagger)
        val merged = mergeTagInfo(withWavFallback, retrieverDefaults)

        assertEquals("TagLib title", merged.title)
        assertEquals("ID3 artist", merged.artist)
        assertEquals("ID3 album", merged.album)
        assertEquals("Retriever album artist", merged.albumArtist)
        assertEquals(120, merged.durationSec)
        assertEquals(2001, merged.year)
        assertEquals(11, merged.trackNumber)
        assertEquals(4, merged.discNumber)
    }
}
