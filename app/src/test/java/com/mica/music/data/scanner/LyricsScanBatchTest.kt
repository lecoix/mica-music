package com.mica.music.data.scanner

import com.mica.music.data.LyricsSlots
import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricToken
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsFormat
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.LyricsProbeResult
import com.mica.music.testutil.SongFixtures
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LyricsScanBatchTest {
    @Test
    fun probeParallelismCanGrowWithoutGrowingPersistenceBatch() {
        assertEquals(8, MediaStoreScanner.PROBE_PARALLELISM)
        assertEquals(6, MediaStoreScanner.LYRICS_SCAN_BATCH_SIZE)
    }

    @Test
    fun eightProbeResultsArePersistedAsSixThenTwo() = runTest {
        val songs = List(MediaStoreScanner.PROBE_PARALLELISM) { index ->
            val song = SongFixtures.song("parallel-$index")
            ScannedSong(song, LyricsProbeResult.Complete(LyricsSlots(embedded = song.lyricsDocument)))
        }
        val persistedBatchSizes = mutableListOf<Int>()

        val retained = persistScannedLyricsBatches(songs) { batch ->
            persistedBatchSizes += batch.completed.size
        }

        assertEquals(listOf(6, 2), persistedBatchSizes)
        assertEquals(8, retained.size)
    }

    @Test
    fun persistedBatchIsReleasedBeforeScannerKeepsCatalogSongs() = runTest {
        val songs = (0 until MediaStoreScanner.LYRICS_SCAN_BATCH_SIZE).map { index ->
            val song = SongFixtures.song("song-$index")
            ScannedSong(song, LyricsProbeResult.Complete(LyricsSlots(embedded = song.lyricsDocument)))
        }
        var persisted = 0

        val retained = persistScannedLyricsBatch(songs) { batch -> persisted = batch.completed.size }

        assertEquals(MediaStoreScanner.LYRICS_SCAN_BATCH_SIZE, persisted)
        assertEquals(MediaStoreScanner.LYRICS_SCAN_BATCH_SIZE, retained.size)
        retained.forEach { song ->
            assertFalse(song.lyricsLoaded)
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
                ScannedSong(
                    SongFixtures.song("capacity-$index"),
                    LyricsProbeResult.Complete(LyricsSlots(document, document, document)),
                )
            }
            retained += persistScannedLyricsBatch(batch) { payloads ->
                maxPayloadSongs = maxOf(maxPayloadSongs, payloads.completed.size)
            }
        }

        assertEquals(10_000, retained.size)
        assertEquals(MediaStoreScanner.LYRICS_SCAN_BATCH_SIZE, maxPayloadSongs)
        assertEquals(0, retained.sumOf { it.lyricsDocument.lines.size })
    }

    @Test
    fun fullLengthWordTimedLyricsRemainBoundedToOneSixSongBatch() = runTest {
        val batch = List(MediaStoreScanner.LYRICS_SCAN_BATCH_SIZE) { songIndex ->
            val document = fullLengthWordTimedDocument(songIndex)
            ScannedSong(
                SongFixtures.song("full-$songIndex"),
                LyricsProbeResult.Complete(LyricsSlots(document, document, document)),
            )
        }
        var payloadDocuments = 0

        val retained = persistScannedLyricsBatch(batch) { payload ->
            payloadDocuments = payload.completed.sumOf { it.slots.entries().size }
        }

        assertEquals(MediaStoreScanner.LYRICS_SCAN_BATCH_SIZE * 3, payloadDocuments)
        assertEquals(0, retained.sumOf { it.lyricsDocument.lines.size })
    }

    @Test
    fun reusedSongsAreNotWrittenToLyricsStaging() = runTest {
        val reused = ScannedSong(SongFixtures.song("reused"))
        val probedSong = SongFixtures.song("probed")
        val probed = ScannedSong(
            probedSong,
            LyricsProbeResult.Complete(LyricsSlots(embedded = probedSong.lyricsDocument)),
        )
        var stagedIds = emptyList<String>()

        persistScannedLyricsBatch(listOf(reused, probed)) { payloads ->
            stagedIds = payloads.completed.map { it.songId }
        }

        assertEquals(listOf("probed"), stagedIds)
    }

    @Test
    fun readFailedSongIsCountedButNeverWritten() = runTest {
        val failures = AtomicInteger(0)
        val failed = ScannedSong(SongFixtures.song("failed"), LyricsProbeResult.ReadFailed)
        val completedSong = SongFixtures.song("complete")
        val completed = ScannedSong(
            completedSong,
            LyricsProbeResult.Complete(LyricsSlots(embedded = completedSong.lyricsDocument)),
        )
        var writtenIds = emptyList<String>()

        persistScannedLyricsBatch(listOf(failed, completed), failures) { payloads ->
            writtenIds = payloads.completed.map { it.songId }
            assertEquals(1, payloads.readFailedCount)
        }

        assertEquals(1, failures.get())
        assertEquals(listOf("complete"), writtenIds)
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

    private fun fullLengthWordTimedDocument(songIndex: Int): LyricsDocument = LyricsDocument(
        format = LyricsFormat.TTML,
        origin = LyricsOrigin.EXTERNAL,
        lines = List(400) { lineIndex ->
            val tokens = List(12) { tokenIndex ->
                LyricToken("word-$tokenIndex", lineIndex * 2_000 + tokenIndex * 120)
            }
            LyricLineNode(
                id = "full-$songIndex-$lineIndex",
                startMs = lineIndex * 2_000,
                parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, tokens.joinToString(" ") { it.text })),
                tokens = tokens,
            )
        },
    )
}
