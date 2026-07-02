package com.mica.music.media

import androidx.media3.common.MediaMetadata
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsSync
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationLyricsTest {

    @Test
    fun subtitleJoinsTitleAndArtist() {
        assertEquals("晴天 - 周杰伦", NotificationLyrics.subtitle("晴天", "周杰伦"))
        assertEquals("晴天", NotificationLyrics.subtitle("晴天", ""))
        assertEquals("周杰伦", NotificationLyrics.subtitle("", "周杰伦"))
    }

    @Test
    fun metadataWithLyricSwapsTitleAndArtist() {
        val song = SongFixtures.song(id = "lyric", title = "晴天").copy(
            artist = "周杰伦",
            lyrics = listOf(
                LyricLine(timeMs = 0, text = "故事的小黄花"),
                LyricLine(timeMs = 5_000, text = "从出生那年就飘着"),
            ),
        )
        val base = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .build()

        val metadata = NotificationLyrics.metadataWithLyric(
            song,
            lyricIndex = 1,
            base,
            NotificationLyrics.DisplayOptions(
                splitEnabled = true,
                bilingualMode = LyricsBilingualDisplayMode.ALL,
            ),
        )

        assertEquals("从出生那年就飘着", metadata?.title?.toString())
        assertEquals("从出生那年就飘着", metadata?.displayTitle?.toString())
        assertEquals("晴天 - 周杰伦", metadata?.artist?.toString())
        assertEquals(
            "晴天",
            metadata?.extras?.getString(SongMediaItemCodec.canonicalTitleExtraKey()),
        )
    }

    @Test
    fun defaultPlaybackMetadataRestoresSongFields() {
        val song = SongFixtures.song(id = "restore", title = "晴天").copy(artist = "周杰伦")
        val polluted = MediaMetadata.Builder()
            .setTitle("歌词行")
            .setArtist("晴天 - 周杰伦")
            .build()

        val restored = NotificationLyrics.defaultPlaybackMetadata(song, polluted)

        assertEquals("晴天", restored.title?.toString())
        assertEquals("晴天", restored.displayTitle?.toString())
        assertEquals("周杰伦", restored.artist?.toString())
    }

    @Test
    fun lyricIndexRequiresTimedLyrics() {
        val untimed = listOf(LyricLine(timeMs = 0, text = "纯文本"))
        assertEquals(-1, NotificationLyrics.lyricIndexForPosition(untimed, positionMs = 0))

        val timed = listOf(
            LyricLine(timeMs = 0, text = "第一句"),
            LyricLine(timeMs = 3_000, text = "第二句"),
        )
        val index = NotificationLyrics.lyricIndexForPosition(timed, positionMs = 3_100)
        assertEquals(1, index)
        assertEquals(
            "第二句",
            NotificationLyrics.lyricLineText(
                timed,
                index,
                NotificationLyrics.DisplayOptions(
                    splitEnabled = true,
                    bilingualMode = LyricsBilingualDisplayMode.ALL,
                ),
            ),
        )
    }

    @Test
    fun lyricLineTextRespectsBilingualDisplayMode() {
        val lyrics = listOf(
            LyricLine(timeMs = 0, text = "hello / world"),
        )
        val display = NotificationLyrics.DisplayOptions(
            splitEnabled = true,
            bilingualMode = LyricsBilingualDisplayMode.ORIGINAL,
        )
        assertEquals("hello", NotificationLyrics.lyricLineText(lyrics, 0, display))

        val translationOnly = display.copy(bilingualMode = LyricsBilingualDisplayMode.TRANSLATION)
        assertEquals("world", NotificationLyrics.lyricLineText(lyrics, 0, translationOnly))

        val all = display.copy(bilingualMode = LyricsBilingualDisplayMode.ALL)
        assertEquals("hello world", NotificationLyrics.lyricLineText(lyrics, 0, all))
    }

    @Test
    fun lyricLineTextIgnoresBilingualModeWhenSplitDisabled() {
        val lyrics = listOf(LyricLine(timeMs = 0, text = "hello / world"))
        val display = NotificationLyrics.DisplayOptions(
            splitEnabled = false,
            bilingualMode = LyricsBilingualDisplayMode.ORIGINAL,
        )
        assertEquals("hello / world", NotificationLyrics.lyricLineText(lyrics, 0, display))
    }

    @Test
    fun decodeUsesCanonicalTitleWhenNotificationShowsLyric() {
        val song = SongFixtures.song(id = "codec", title = "晴天").copy(artist = "周杰伦")
        val item = SongMediaItemCodec.encode(song)
        val lyricMetadata = item.mediaMetadata.buildUpon()
            .setTitle("故事的小黄花")
            .setArtist("晴天 - 周杰伦")
            .build()
        val lyricItem = item.buildUpon().setMediaMetadata(lyricMetadata).build()

        assertEquals("晴天", SongMediaItemCodec.decode(lyricItem)?.title)
    }

    @Test
    fun metadataWithLyricReturnsNullForBlankLine() {
        val song = SongFixtures.song(id = "blank").copy(
            lyrics = listOf(LyricLine(timeMs = 0, text = "   ")),
        )
        val base = MediaMetadata.Builder().setTitle(song.title).build()

        assertNull(
            NotificationLyrics.metadataWithLyric(
                song,
                lyricIndex = 0,
                base,
                NotificationLyrics.DisplayOptions(
                    splitEnabled = true,
                    bilingualMode = LyricsBilingualDisplayMode.ALL,
                ),
            ),
        )
    }

    @Test
    fun signatureIncludesDisplayLine() {
        assertEquals("song-a:3:hello world", NotificationLyrics.signature("song-a", 3, "hello world"))
    }

    @Test
    fun lyricIndexUsesLeadMs() {
        val lyrics = listOf(
            LyricLine(timeMs = 1_000, text = "第一句"),
            LyricLine(timeMs = 5_000, text = "第二句"),
        )
        val index = NotificationLyrics.lyricIndexForPosition(
            lyrics,
            positionMs = 5_000 - LyricsSync.LEAD_MS,
        )
        assertEquals(1, index)
    }
}
