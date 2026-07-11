package com.mica.music.data

import org.junit.Assert.assertEquals
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
}
