package com.mica.music.ui.screens.player

import com.mica.music.data.LyricLine
import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsFormat
import com.mica.music.data.LyricsSession
import com.mica.music.data.renderStateAt
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoStackImmersiveCaptionTest {
    private val song = SongFixtures.song(id = "caption", title = "晴天").copy(artist = "周杰伦")

    @Test
    fun fallsBackToSongWhenPausedOrDisabled() {
        val state = listOf(LyricLine(1_000, "歌词 / lyric")).renderStateAt(1_500)

        assertEquals(
            PhotoStackImmersiveCaption("晴天", "周杰伦"),
            photoStackImmersiveCaption(
                song = song,
                isPlaying = false,
                lyricsInTitleEnabled = true,
                renderState = state,
                splitEnabled = true,
            ),
        )
        assertEquals(
            PhotoStackImmersiveCaption("晴天", "周杰伦"),
            photoStackImmersiveCaption(
                song = song,
                isPlaying = true,
                lyricsInTitleEnabled = false,
                renderState = state,
                splitEnabled = true,
            ),
        )
    }

    @Test
    fun monolingualLyricUsesTitleArtistSubtitle() {
        val state = listOf(LyricLine(1_000, "故事的小黄花")).renderStateAt(1_500)

        assertEquals(
            PhotoStackImmersiveCaption("故事的小黄花", "晴天 - 周杰伦"),
            photoStackImmersiveCaption(
                song = song,
                isPlaying = true,
                lyricsInTitleEnabled = true,
                renderState = state,
                splitEnabled = true,
            ),
        )
    }

    @Test
    fun bilingualLyricPutsTranslationInSubtitle() {
        val state = listOf(LyricLine(1_000, "故事的小黄花 / the little yellow flower")).renderStateAt(1_500)

        assertEquals(
            PhotoStackImmersiveCaption("故事的小黄花", "the little yellow flower"),
            photoStackImmersiveCaption(
                song = song,
                isPlaying = true,
                lyricsInTitleEnabled = true,
                renderState = state,
                splitEnabled = true,
            ),
        )
    }

    @Test
    fun structuredTranslationDoesNotHonorBilingualDisplayMode() {
        val document = LyricsDocument(
            format = LyricsFormat.TTML,
            lines = listOf(
                LyricLineNode(
                    id = "0",
                    startMs = 1_000,
                    endMs = 2_000,
                    parts = listOf(
                        LyricTextPart(LyricTextRole.READING, "ai wa"),
                        LyricTextPart(LyricTextRole.ORIGINAL, "愛は"),
                        LyricTextPart(LyricTextRole.TRANSLATION, "爱"),
                    ),
                ),
            ),
        )
        val state = LyricsSession(document).snapshotAt(1_500)

        assertEquals(
            PhotoStackImmersiveCaption("愛は", "爱"),
            photoStackImmersiveCaption(
                song = song,
                isPlaying = true,
                lyricsInTitleEnabled = true,
                renderState = state,
                splitEnabled = true,
            ),
        )
    }

    @Test
    fun splitDisabledTreatsBilingualLineAsMonolingual() {
        val document = LyricsDocument(
            format = LyricsFormat.TTML,
            lines = listOf(
                LyricLineNode(
                    id = "0",
                    startMs = 1_000,
                    parts = listOf(
                        LyricTextPart(LyricTextRole.ORIGINAL, "原文"),
                        LyricTextPart(LyricTextRole.TRANSLATION, "译文"),
                    ),
                ),
            ),
        )
        val state = LyricsSession(document).snapshotAt(1_500)

        assertEquals(
            PhotoStackImmersiveCaption("原文\u2009译文", "晴天 - 周杰伦"),
            photoStackImmersiveCaption(
                song = song,
                isPlaying = true,
                lyricsInTitleEnabled = true,
                renderState = state,
                splitEnabled = false,
            ),
        )
    }

    @Test
    fun missingActiveLineFallsBackToSong() {
        val state = listOf(LyricLine(0, "untimed")).renderStateAt(500)

        assertEquals(
            PhotoStackImmersiveCaption("晴天", "周杰伦"),
            photoStackImmersiveCaption(
                song = song,
                isPlaying = true,
                lyricsInTitleEnabled = true,
                renderState = state,
                splitEnabled = true,
            ),
        )
    }
}
