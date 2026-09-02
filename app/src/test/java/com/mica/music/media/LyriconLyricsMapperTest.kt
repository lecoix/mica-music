package com.mica.music.media

import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricToken
import com.mica.music.data.LyricsDocument
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyriconLyricsMapperTest {
    @Test
    fun mapsSemanticTracksAndFillsLineAndWordEnds() {
        val song = SongFixtures.song(id = "mapped", durationSec = 3).copy(
            title = "Title",
            artist = "Artist",
        )
        val document = LyricsDocument(
            lines = listOf(
                LyricLineNode(
                    id = "line-1",
                    startMs = 0,
                    parts = listOf(
                        LyricTextPart(LyricTextRole.READING, "romaji"),
                        LyricTextPart(LyricTextRole.ORIGINAL, "原文"),
                        LyricTextPart(LyricTextRole.TRANSLATION, "translation"),
                        LyricTextPart(LyricTextRole.EXTRA, "ignored"),
                    ),
                    tokens = listOf(
                        LyricToken("原", 0, partRole = LyricTextRole.ORIGINAL),
                        LyricToken("文", 400, partRole = LyricTextRole.ORIGINAL),
                        LyricToken("trans", 0, partRole = LyricTextRole.TRANSLATION),
                        LyricToken("lation", 500, partRole = LyricTextRole.TRANSLATION),
                    ),
                ),
                LyricLineNode(
                    id = "line-2",
                    startMs = 1_000,
                    endMs = 2_000,
                    parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "next")),
                ),
                LyricLineNode(
                    id = "line-3",
                    startMs = 2_000,
                    parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "last")),
                ),
            ),
        )

        val mapped = LyriconLyricsMapper.mapSong(song, document, effectiveOffsetMs = 0)

        assertEquals("mapped", mapped.id)
        assertEquals("Title", mapped.name)
        assertEquals("Artist", mapped.artist)
        assertEquals(3_000L, mapped.duration)
        assertEquals(3, mapped.lyrics?.size)
        val first = mapped.lyrics.orEmpty()[0]
        assertEquals(0L, first.begin)
        assertEquals(1_000L, first.end)
        assertEquals("原文", first.text)
        assertEquals("translation", first.translation)
        assertEquals("romaji", first.roma)
        assertEquals(listOf(0L to 400L, 400L to 1_000L), first.words.orEmpty().map { it.begin to it.end })
        assertEquals(
            listOf(0L to 500L, 500L to 1_000L),
            first.translationWords.orEmpty().map { it.begin to it.end },
        )
        assertEquals(3_000L, mapped.lyrics.orEmpty()[2].end)
    }

    @Test
    fun promotesDisplayOnlyBilingualSplitToLyriconTranslation() {
        val song = SongFixtures.song(id = "plain-bilingual", durationSec = 3)
        val document = LyricsDocument(
            lines = listOf(
                LyricLineNode(
                    id = "line",
                    startMs = 500,
                    endMs = 1_500,
                    parts = listOf(
                        LyricTextPart(LyricTextRole.ORIGINAL, "plain original\u2009普通翻译"),
                    ),
                ),
            ),
        )

        val mapped = LyriconLyricsMapper.mapSong(song, document, effectiveOffsetMs = 0)

        val line = mapped.lyrics.orEmpty().single()
        assertEquals("plain original", line.text)
        assertEquals("普通翻译", line.translation)
        assertNull(line.words)
        assertNull(line.translationWords)
    }

    @Test
    fun positiveOffsetMovesLyricTimelineEarlierAndNegativeMovesItLater() {
        val song = SongFixtures.song(id = "offset", durationSec = 4)
        val document = LyricsDocument(
            lines = listOf(
                LyricLineNode(
                    id = "line",
                    startMs = 1_000,
                    endMs = 2_000,
                    parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "line")),
                ),
            ),
        )

        val earlier = LyriconLyricsMapper.mapSong(song, document, effectiveOffsetMs = 500)
        val later = LyriconLyricsMapper.mapSong(song, document, effectiveOffsetMs = -500)

        assertEquals(500L, earlier.lyrics.orEmpty().single().begin)
        assertEquals(1_500L, earlier.lyrics.orEmpty().single().end)
        assertEquals(1_500L, later.lyrics.orEmpty().single().begin)
        assertEquals(2_500L, later.lyrics.orEmpty().single().end)
    }

    @Test
    fun ignoresBlankOriginalLinesAndInvalidWordIntervals() {
        val song = SongFixtures.song(id = "invalid", durationSec = 3)
        val document = LyricsDocument(
            lines = listOf(
                LyricLineNode(
                    id = "translation-only",
                    startMs = 0,
                    endMs = 500,
                    parts = listOf(LyricTextPart(LyricTextRole.TRANSLATION, "only translation")),
                ),
                LyricLineNode(
                    id = "timed",
                    startMs = 500,
                    endMs = 1_500,
                    parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "AB")),
                    tokens = listOf(
                        LyricToken("A", startMs = 700, endMs = 600),
                        LyricToken("B", startMs = 900),
                    ),
                ),
            ),
        )

        val mapped = LyriconLyricsMapper.mapSong(song, document, effectiveOffsetMs = 0)

        assertEquals(1, mapped.lyrics?.size)
        val line = mapped.lyrics.orEmpty().single()
        assertEquals("AB", line.text)
        assertEquals(listOf("B"), line.words.orEmpty().map { it.text })
        assertNull(line.translation)
        assertNull(line.roma)
    }

    @Test
    fun clampsShiftedTimingToSongBounds() {
        val song = SongFixtures.song(id = "clamp", durationSec = 2)
        val document = LyricsDocument(
            lines = listOf(
                LyricLineNode(
                    id = "line",
                    startMs = 0,
                    endMs = 1_000,
                    parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "line")),
                ),
            ),
        )

        val mapped = LyriconLyricsMapper.mapSong(song, document, effectiveOffsetMs = 500)

        val line = mapped.lyrics.orEmpty().single()
        assertEquals(0L, line.begin)
        assertEquals(500L, line.end)
    }
}
