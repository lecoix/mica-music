package com.mica.music.media.dsd

import com.mica.music.media.dsf.DsfExtractorPacketFacts
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectDsdTrackTransitionPolicyTest {
    private val dsd128 = facts(5_644_800)
    private val dsd64 = facts(2_822_400)

    @Test
    fun samePlanRetainsCarrierWhetherPlayingOrPaused() {
        assertEquals(
            DirectDsdTrackTransitionMode.RETAINED_SAME_PLAN,
            DirectDsdTrackTransitionPolicy.decide(dsd128, dsd128.copy(), isPlaying = true),
        )
        assertEquals(
            DirectDsdTrackTransitionMode.RETAINED_SAME_PLAN,
            DirectDsdTrackTransitionPolicy.decide(dsd128, dsd128.copy(), isPlaying = false),
        )
    }

    @Test
    fun rateChangeUsesFreshRuntimeOnlyWhilePlaying() {
        assertEquals(
            DirectDsdTrackTransitionMode.FRESH_RUNTIME,
            DirectDsdTrackTransitionPolicy.decide(dsd64, dsd128, isPlaying = true),
        )
        assertEquals(
            DirectDsdTrackTransitionMode.DEFERRED_PAUSED_FRESH_RUNTIME,
            DirectDsdTrackTransitionPolicy.decide(dsd64, dsd128, isPlaying = false),
        )
    }

    @Test
    fun firstDirectStreamIsInitialRegardlessOfPlaybackState() {
        assertEquals(
            DirectDsdTrackTransitionMode.INITIAL,
            DirectDsdTrackTransitionPolicy.decide(null, dsd128, isPlaying = false),
        )
    }

    private fun facts(rate: Int) = DsfExtractorPacketFacts(
        sourceSampleRateHz = rate,
        channelCount = 2,
        sourceBitOrder = DsdSourceBitOrder.LSB_FIRST,
    )
}
