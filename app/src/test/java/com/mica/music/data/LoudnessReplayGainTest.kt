package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessReplayGainTest {
    @Test
    fun trackModeUsesMicaAnalysisOnlyWhenTrackTagIsMissing() {
        val analysis = LoudnessAnalysis(
            integratedLufs = -12f,
            samplePeak = 0.8f,
            trackGainDb = -6f,
            sourceSizeBytes = 10L,
            sourceModifiedMs = 20L,
            analyzerRevision = LoudnessAnalysis.CURRENT_ANALYZER_REVISION,
        )

        val scanned = ReplayGainPolicy.resolve(ReplayGainTags(), ReplayGainMode.TRACK, analysis)
        assertEquals(ReplayGainSource.TRACK_SCAN, scanned.source)
        assertEquals(0.501, scanned.linearFactor.toDouble(), 0.002)

        val tagged = ReplayGainPolicy.resolve(
            ReplayGainTags(trackGainDb = -3f, trackPeak = 0.9f),
            ReplayGainMode.TRACK,
            analysis,
        )
        assertEquals(ReplayGainSource.TRACK_TAG, tagged.source)
        assertEquals(0.708, tagged.linearFactor.toDouble(), 0.002)
    }

    @Test
    fun quietTrackScanCanApplyPositiveNormalizationGain() {
        val analysis = LoudnessAnalysis(
            integratedLufs = -24f,
            samplePeak = 1f,
            trackGainDb = 6f,
            sourceSizeBytes = 10L,
            sourceModifiedMs = 20L,
            analyzerRevision = LoudnessAnalysis.CURRENT_ANALYZER_REVISION,
        )

        val applied = ReplayGainPolicy.resolve(ReplayGainTags(), ReplayGainMode.TRACK, analysis)

        assertEquals(ReplayGainSource.TRACK_SCAN, applied.source)
        assertEquals(1.995, applied.linearFactor.toDouble(), 0.002)
        assertTrue(applied.modifiesSignal)
    }

    @Test
    fun albumModeDoesNotPretendTrackScanIsAlbumAnalysis() {
        val analysis = LoudnessAnalysis(
            integratedLufs = -12f,
            samplePeak = 0.8f,
            trackGainDb = -6f,
            sourceSizeBytes = 10L,
            sourceModifiedMs = 20L,
            analyzerRevision = LoudnessAnalysis.CURRENT_ANALYZER_REVISION,
        )
        val applied = ReplayGainPolicy.resolve(ReplayGainTags(), ReplayGainMode.ALBUM, analysis)
        assertEquals(ReplayGainSource.MISSING_TAG, applied.source)
        assertEquals(1.0, applied.linearFactor.toDouble(), 0.0)
    }

    @Test
    fun scanValidityTracksSourceFingerprint() {
        val song = song(sizeBytes = 10L, modifiedMs = 20L)
        val analysis = LoudnessAnalysis(
            integratedLufs = -18f,
            samplePeak = 0.5f,
            trackGainDb = 0f,
            sourceSizeBytes = 10L,
            sourceModifiedMs = 20L,
            analyzerRevision = LoudnessAnalysis.CURRENT_ANALYZER_REVISION,
        )
        assertTrue(analysis.matches(song))
        assertFalse(analysis.matches(song.copy(dateModifiedMs = 21L)))
    }

    private fun song(sizeBytes: Long, modifiedMs: Long): Song = Song(
        id = "song",
        title = "Song",
        artist = "Artist",
        album = "Album",
        durationSec = 180,
        metadata = TrackMetadata(
            containerName = "FLAC",
            sampleRateHz = 48_000,
            bitsPerSample = 24,
            bitrateKbps = 1_000,
            channelCount = 2,
            playbackMimeType = "audio/flac",
        ),
        albumArtUri = null,
        coverColorArgb = 0,
        mediaUri = "file:///music/song.flac",
        sizeBytes = sizeBytes,
        dateModifiedMs = modifiedMs,
    )
}
