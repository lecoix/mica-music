package com.mica.music.data.local

import org.junit.Assert.assertTrue
import org.junit.Test

class StorageDiagnosticsTest {
    @Test
    fun albumArtStatsExposeSizeDimensionsAndExactDuplicateSavings() {
        val stats = summarizeAlbumArtObservations(
            observations = listOf(
                AlbumArtFileObservation(100, widthPx = 100, heightPx = 80, contentDigest = "same"),
                AlbumArtFileObservation(100, widthPx = 100, heightPx = 80, contentDigest = "same"),
                AlbumArtFileObservation(300, widthPx = 400, heightPx = 300, contentDigest = "large"),
                AlbumArtFileObservation(500, widthPx = null, heightPx = null, contentDigest = null),
            ),
            hashFailures = 1,
        )

        assertTrue(stats.fileCount == 4)
        assertTrue(stats.averageFileBytes == 250L)
        assertTrue(stats.p50FileBytes == 100L)
        assertTrue(stats.p95FileBytes == 500L)
        assertTrue(stats.maxFileBytes == 500L)
        assertTrue(stats.readableImageFiles == 3)
        assertTrue(stats.unknownDimensionFiles == 1)
        assertTrue(stats.p50LongestEdgePx == 100)
        assertTrue(stats.p95LongestEdgePx == 400)
        assertTrue(stats.maxLongestEdgePx == 400)
        assertTrue(stats.hashedFiles == 3)
        assertTrue(stats.hashFailures == 1)
        assertTrue(stats.duplicateGroups == 1)
        assertTrue(stats.redundantFiles == 1)
        assertTrue(stats.deduplicatableBytes == 100L)
    }

    @Test
    fun reportSeparatesPhysicalFilesFromLyricsPayloadsAndAlbumArt() {
        val snapshot = StorageDiagnosticsSnapshot(
            privateDataBytes = 100L * 1024 * 1024,
            cacheBytes = 512L * 1024,
            filesBytes = 2L * 1024 * 1024,
            noBackupBytes = 60L * 1024 * 1024,
            albumArtBytes = 58L * 1024 * 1024,
            albumArtStats = AlbumArtStorageStats(
                fileCount = 120,
                totalFileBytes = 58L * 1024 * 1024,
                averageFileBytes = 494_660,
                p50FileBytes = 400L * 1024,
                p95FileBytes = 900L * 1024,
                maxFileBytes = 2L * 1024 * 1024,
                readableImageFiles = 118,
                unknownDimensionFiles = 2,
                p50LongestEdgePx = 1200,
                p95LongestEdgePx = 3000,
                maxLongestEdgePx = 4000,
                hashedFiles = 119,
                hashFailures = 1,
                duplicateGroups = 20,
                redundantFiles = 70,
                deduplicatableBytes = 30L * 1024 * 1024,
            ),
            legacyAlbumArtBytes = 0,
            databaseFilesBytes = 35L * 1024 * 1024,
            databaseMainBytes = 30L * 1024 * 1024,
            databaseWalBytes = 5L * 1024 * 1024,
            databaseShmBytes = 0,
            databaseAllocatedBytes = 30L * 1024 * 1024,
            databaseFreeBytes = 4L * 1024 * 1024,
            songCount = 568,
            lyricsBySlot = listOf(
                StoragePayloadStats("EXTERNAL_TTML", rows = 400, payloadBytes = 20L * 1024 * 1024),
                StoragePayloadStats("EMBEDDED", rows = 100, payloadBytes = 3L * 1024 * 1024),
            ),
            legacyLyrics = StoragePayloadStats("songs.lyricsJson", rows = 25, payloadBytes = 2L * 1024 * 1024),
            pendingLyrics = StoragePayloadStats("song_lyrics_pending", rows = 0, payloadBytes = 0),
        )

        val report = snapshot.toReportText()

        assertTrue(report.contains("Private app data: 100.00 MiB"))
        assertTrue(report.contains("Album art: 58.00 MiB"))
        assertTrue(report.contains("Album art files: 120"))
        assertTrue(report.contains("size: avg=483.07 KiB p50=400.00 KiB p95=900.00 KiB max=2.00 MiB"))
        assertTrue(report.contains("long edge: readable=118 unknown=2 p50=1200px p95=3000px max=4000px"))
        assertTrue(report.contains("exact duplicates: groups=20 redundantFiles=70 reclaimable=30.00 MiB"))
        assertTrue(report.contains("hashing: hashed=119 failed=1"))
        assertTrue(report.contains("Database files: 35.00 MiB"))
        assertTrue(report.contains("SQLite free pages: 4.00 MiB"))
        assertTrue(report.contains("EXTERNAL_TTML: rows=400 payload=20.00 MiB"))
        assertTrue(report.contains("songs.lyricsJson: rows=25 payload=2.00 MiB"))
    }
}
