package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGainTest {
    @Test
    fun parsesTagsCaseInsensitively() {
        val tags = ReplayGainTags.fromProperties(
            mapOf(
                "replaygain_track_gain" to arrayOf("-6.00 dB"),
                "REPLAYGAIN_TRACK_PEAK" to arrayOf("0.75"),
                "REPLAYGAIN_ALBUM_GAIN" to arrayOf("-3.00 dB"),
            ),
        )

        assertEquals(-6f, tags.trackGainDb)
        assertEquals(0.75f, tags.trackPeak)
        assertEquals(-3f, tags.albumGainDb)
    }

    @Test
    fun policyDefaultsToUnityAndNeverAmplifies() {
        val tags = ReplayGainTags(trackGainDb = 6f, trackPeak = 0.5f)

        assertEquals(1f, ReplayGainPolicy.linearGain(tags, ReplayGainMode.OFF))
        assertEquals(1f, ReplayGainPolicy.linearGain(ReplayGainTags(), ReplayGainMode.TRACK))
        assertEquals(1f, ReplayGainPolicy.linearGain(tags, ReplayGainMode.TRACK))
    }

    @Test
    fun policyAppliesSelectedGainAndPeakLimit() {
        val tags = ReplayGainTags(
            trackGainDb = -6.0206f,
            trackPeak = 1f,
            albumGainDb = -3f,
            albumPeak = 2f,
        )

        assertEquals(0.5f, ReplayGainPolicy.linearGain(tags, ReplayGainMode.TRACK), 0.0001f)
        assertEquals(0.5f, ReplayGainPolicy.linearGain(tags, ReplayGainMode.ALBUM), 0.0001f)
    }

    @Test
    fun decisionReportsActualFactorAndTagSource() {
        val decision = ReplayGainPolicy.resolve(
            ReplayGainTags(trackGainDb = -6.0206f, trackPeak = 1f),
            ReplayGainMode.TRACK,
        )

        assertEquals(ReplayGainMode.TRACK, decision.mode)
        assertEquals(ReplayGainSource.TRACK_TAG, decision.source)
        assertEquals(0.5f, decision.linearFactor, 0.0001f)
        assertTrue(decision.modifiesSignal)
    }

    @Test
    fun albumModeWithoutAlbumTagStaysUnityInsteadOfFallingBackToTrack() {
        val decision = ReplayGainPolicy.resolve(
            ReplayGainTags(trackGainDb = -6f),
            ReplayGainMode.ALBUM,
        )

        assertEquals(ReplayGainMode.ALBUM, decision.mode)
        assertEquals(ReplayGainSource.MISSING_TAG, decision.source)
        assertEquals(1f, decision.linearFactor)
        assertFalse(decision.modifiesSignal)
    }

    @Test
    fun selectedPositiveGainReportsTagButNotSignalModification() {
        val decision = ReplayGainPolicy.resolve(
            ReplayGainTags(trackGainDb = 6f, trackPeak = 0.5f),
            ReplayGainMode.TRACK,
        )

        assertEquals(ReplayGainSource.TRACK_TAG, decision.source)
        assertEquals(1f, decision.linearFactor)
        assertFalse(decision.modifiesSignal)
    }
}
