package com.mica.music.media.dsd

import com.mica.music.media.dsf.DsfExtractorPacketFacts

enum class DirectDsdTrackTransitionMode {
    INITIAL,
    RETAINED_SAME_PLAN,
    FRESH_RUNTIME,
    DEFERRED_PAUSED_FRESH_RUNTIME,
}

object DirectDsdTrackTransitionPolicy {
    fun decide(
        oldFacts: DsfExtractorPacketFacts?,
        newFacts: DsfExtractorPacketFacts,
        isPlaying: Boolean,
    ): DirectDsdTrackTransitionMode {
        if (oldFacts == null) return DirectDsdTrackTransitionMode.INITIAL
        val sameCarrierPlan =
            oldFacts.sourceSampleRateHz == newFacts.sourceSampleRateHz &&
                oldFacts.channelCount == newFacts.channelCount
        if (sameCarrierPlan) return DirectDsdTrackTransitionMode.RETAINED_SAME_PLAN
        return if (isPlaying) {
            DirectDsdTrackTransitionMode.FRESH_RUNTIME
        } else {
            DirectDsdTrackTransitionMode.DEFERRED_PAUSED_FRESH_RUNTIME
        }
    }
}
