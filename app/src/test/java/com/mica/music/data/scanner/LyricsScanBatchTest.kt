package com.mica.music.data.scanner

import com.mica.music.data.LyricsSlots
import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricToken
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsFormat
import com.mica.music.data.LyricsOrigin
import com.mica.music.testutil.SongFixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsScanBatchTest {
    @Test
    fun persistedBatchIsReleasedBeforeScannerKeepsCatalogSongs() = runTest {
        val songs = (0 until MediaStoreScanner.LYRICS_SCAN_BATCH_SIZE).map { index ->
            val song = SongFixtures.song("song-$index")
            song.copy(scannedLyrics = LyricsSlots(embedded = song.lyricsDocument))
        }
        var persisted = 0

        val retained = persistScannedLyricsBatch(songs) { batch -> persisted = batch.size }

        assertEquals(MediaStoreScanner.LYRICS_SCAN_BATCH_SIZE, persisted)
        assertEquals(MediaStoreScanner.LYRICS_SCAN_BATCH_SIZE, retained.size)
        retained.forEach { song ->
            assertFalse(song.lyricsLoaded)
            assertNull(song.scannedLyrics)
            assertEquals(0, song.lyricsDocument.lines.size)
        }
    }

    @Test
    fun tenThousandWordTimedSongsRetainOnlyLightweightCatalogRows() = runTest {
        val retained = ArrayList<com.mica.music.data.Song>(10_000)
        var maxPayloadSongs = 0

        repeat(10_000 / MediaStoreScanner.LYRICS_SCAN_BATCH_SIZE + 1) { batchIndex ->
            val first = batchIndex * MediaStoreScanner.LYRICS_SCAN_BATCH_SIZE
            if (first >= 10_000) return@repeat
            val batch = (first until minOf(first + MediaStoreScanner.LYRICS_SCAN_BATCH_SIZE, 10_000)).map { index ->
                val document = wordTimedDocument(index)
                SongFixtures.song("capacity-$index").copy(
                    scannedLyrics = LyricsSlots(document, document, document),
                )
            }
            retained += persistScannedLyricsBatch(batch) { payloads ->
                maxPayloadSongs = maxOf(maxPayloadSongs, payloads.size)
            }
        }

        assertEquals(10_000, retained.size)
        assertEquals(MediaStoreScanner.LYRICS_SCAN_BATCH_SIZE, maxPayloadSongs)
        assertEquals(0, retained.sumOf { it.lyricsDocument.lines.size })
        assertEquals(0, retained.count { it.scannedLyrics != null })
    }

    private fun wordTimedDocument(songIndex: Int): LyricsDocument = LyricsDocument(
        format = LyricsFormat.TTML,
        origin = LyricsOrigin.EXTERNAL,
        lines = List(20) { lineIndex ->
            val tokens = List(8) { tokenIndex ->
                LyricToken("word-$tokenIndex", lineIndex * 2_000 + tokenIndex * 200)
            }
            LyricLineNode(
                id = "$songIndex-$lineIndex",
                startMs = lineIndex * 2_000,
                parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, tokens.joinToString(" ") { it.text })),
                tokens = tokens,
            )
        },
    )
}
