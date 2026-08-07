package com.mica.music.data.local

import com.mica.music.testutil.SongFixtures
import com.mica.music.data.LyricCue
import com.mica.music.data.LyricsFormat
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.toLegacyLyricLines
import com.mica.music.data.toLyricsDocumentCompat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SongEntityTest {

    @Test
    fun songRoundTripPreservesPersistedFieldsAndLyrics() {
        val song = SongFixtures.song(id = "round-trip", queueOrder = 4).copy(
            discNumber = 2,
            releaseDate = "2024-02-29",
            embeddedLyricsProbeRevision = "1\u0001round-trip\u00011000000\u00012000",
        )
        val entity = song.toEntity(queueOrder = 7)
        val restored = entity.toSong()

        assertEquals(song.copy(totalListenSeconds = 0L, lastPlayedAtMs = 0), restored)
        assertEquals(7, entity.queueOrder)
    }

    @Test
    fun corruptLyricsJsonFallsBackToEmptyList() {
        val entity = SongFixtures.song().toEntity(0).copy(lyricsJson = "{broken")
        assertTrue(entity.toSong().lyricsDocument.lines.isEmpty())
    }

    @Test
    fun songRoundTripPreservesWordCuesAndReadsLegacyJson() {
        val song = SongFixtures.song().copy(
            lyricsDocument = listOf(
                com.mica.music.data.LyricLine(
                    1_000,
                    "hello world",
                    listOf(LyricCue(1_000, "hello "), LyricCue(1_500, "world")),
                    endTimeMs = 1_750,
                ),
            ).toLyricsDocumentCompat(format = LyricsFormat.LRC, origin = LyricsOrigin.EXTERNAL),
        )
        assertEquals(song.lyricsDocument, song.toEntity(0).toSong().lyricsDocument)

        val legacy = song.toEntity(0).copy(lyricsJson = "[{\"t\":1000,\"x\":\"legacy\"}]")
        assertEquals("legacy", legacy.toSong().lyricsDocument.toLegacyLyricLines().single().text)
        assertTrue(legacy.toSong().lyricsDocument.toLegacyLyricLines().single().cues.isEmpty())
    }

    @Test
    fun lyricsAreWrittenAsVersionedDocumentAndKeepLegacyCompatibility() {
        val entity = SongFixtures.song().copy(
            lyricsDocument = listOf(
                com.mica.music.data.LyricLine(1_000, "original\ntranslation"),
            ).toLyricsDocumentCompat(format = LyricsFormat.LRC, origin = LyricsOrigin.EXTERNAL),
        ).toEntity(0)

        val document = JSONObject(entity.lyricsJson)
        assertEquals(2, document.getInt("version"))
        assertEquals("LRC", document.getString("format"))
        assertEquals("EXTERNAL", document.getString("origin"))
        assertEquals("LRC", document.getString("source"))
        val parts = document.getJSONArray("lines").getJSONObject(0).getJSONArray("parts")
        assertEquals(1, parts.length())
        assertEquals("ORIGINAL", parts.getJSONObject(0).getString("role"))
        assertEquals("original\ntranslation", parts.getJSONObject(0).getString("text"))

        val legacy = entity.copy(lyricsJson = "[{\"t\":1000,\"x\":\"legacy\",\"e\":2000}]")
        assertEquals(
            com.mica.music.data.LyricLine(1_000, "legacy", endTimeMs = 2_000),
            legacy.toSong().lyricsDocument.toLegacyLyricLines().single(),
        )
    }

    @Test
    fun versionOneSourceMapsOnlyTheDimensionItActuallyKnows() {
        val base = SongFixtures.song().toEntity(0)
        val line = """[{"role":"ORIGINAL","text":"line"}]"""
        val v1Ttml = base.copy(
            lyricsJson = """{"version":1,"source":"TTML","lines":[{"id":"0","startMs":1,"parts":$line,"tokens":[]}]}""",
        ).toSong().lyricsDocument
        val v1External = base.copy(
            lyricsJson = """{"version":1,"source":"EXTERNAL","lines":[{"id":"0","startMs":1,"parts":$line,"tokens":[]}]}""",
        ).toSong().lyricsDocument

        assertEquals(LyricsFormat.TTML, v1Ttml.format)
        assertEquals(LyricsOrigin.UNKNOWN, v1Ttml.origin)
        assertEquals(LyricsFormat.UNKNOWN, v1External.format)
        assertEquals(LyricsOrigin.EXTERNAL, v1External.origin)
    }

    @Test
    fun futureVersionWithKnownFieldsIsReadAndCanonicalized() {
        val entity = SongFixtures.song().toEntity(0).copy(
            lyricsJson = """{"version":99,"format":"TTML","origin":"EXTERNAL","lines":[{"startMs":1,"parts":[{"role":"ORIGINAL","text":"line"}]}]}""",
        )

        val document = entity.toSong().lyricsDocument

        assertEquals(2, document.version)
        assertEquals(LyricsFormat.TTML, document.format)
        assertEquals(LyricsOrigin.EXTERNAL, document.origin)
        assertEquals("line", document.lines.single().parts.single().text)
    }
}
