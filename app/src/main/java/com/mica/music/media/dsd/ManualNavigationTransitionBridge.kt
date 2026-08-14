package com.mica.music.media.dsd

import android.util.Log
import androidx.media3.common.Format
import com.mica.music.media.dsf.DsfExtractorPacketFacts

data class ManualNavigationDestinationFacts(
    val family: DirectDsdTrackTransportFamily,
    val sampleRateHz: Int,
    val channelCount: Int,
    val formatIdentity: String,
)

data class ManualNavigationTransitionEpoch(
    val requestId: Long,
    val targetMediaId: String,
    val requestedPlaying: Boolean,
    val sourceFamily: DirectDsdTrackTransportFamily,
    val targetFacts: ManualNavigationDestinationFacts? = null,
)

/**
 * Per-Exo-stack authority for explicit cross-item navigation that may retire/recreate renderers.
 * Renderer-local transition state is intentionally not stored here; this bridge only preserves
 * logical navigation intent/currentness across renderer churn.
 */
class ManualNavigationTransitionBridge(
    private val milestone: (String) -> Unit = { Log.i(TAG, it) },
) {
    private var nextRequestId = 0L
    private var active: ManualNavigationTransitionEpoch? = null
    private var currentMediaId: String? = null

    @Synchronized
    fun updateCurrentMediaId(mediaId: String?) {
        if (currentMediaId == mediaId) return
        currentMediaId = mediaId
        milestone("navigationTransition=current-media target=${mediaId ?: "none"}")
    }

    @Synchronized
    fun publish(
        targetMediaId: String,
        requestedPlaying: Boolean,
        sourceFamily: DirectDsdTrackTransportFamily,
    ): ManualNavigationTransitionEpoch {
        require(targetMediaId.isNotBlank())
        val previous = active
        val epoch = ManualNavigationTransitionEpoch(
            requestId = ++nextRequestId,
            targetMediaId = targetMediaId,
            requestedPlaying = requestedPlaying,
            sourceFamily = sourceFamily,
        )
        active = epoch
        milestone(
            "navigationTransition=published request=${epoch.requestId} target=${epoch.targetMediaId} " +
                "playing=${epoch.requestedPlaying} source=${epoch.sourceFamily} " +
                "superseded=${previous?.requestId ?: -1L}",
        )
        return epoch
    }

    @Synchronized
    fun observeDirectRetirementStop(): ManualNavigationTransitionEpoch? {
        val epoch = active ?: return null
        if (epoch.sourceFamily != DirectDsdTrackTransportFamily.DOP) return null
        milestone("navigationTransition=direct-retirement-stop request=${epoch.requestId}")
        return epoch
    }

    @Synchronized
    fun bindDirectDestination(facts: DsfExtractorPacketFacts): ManualNavigationTransitionEpoch? =
        bindDestination(
            ManualNavigationDestinationFacts(
                family = DirectDsdTrackTransportFamily.DOP,
                sampleRateHz = facts.sourceSampleRateHz,
                channelCount = facts.channelCount,
                formatIdentity = "dsf:${facts.sourceSampleRateHz}:${facts.channelCount}:${facts.sourceBitOrder}",
            ),
        )

    @Synchronized
    fun bindPcmDestination(format: Format): ManualNavigationTransitionEpoch? =
        bindDestination(
            ManualNavigationDestinationFacts(
                family = DirectDsdTrackTransportFamily.PCM,
                sampleRateHz = format.sampleRate,
                channelCount = format.channelCount,
                formatIdentity = format.sampleMimeType ?: format.codecs ?: "pcm",
            ),
        )

    @Synchronized
    fun isCurrentDestination(
        requestId: Long,
        facts: ManualNavigationDestinationFacts,
    ): Boolean {
        val epoch = active ?: return false
        return epoch.requestId == requestId &&
            epoch.targetFacts == facts &&
            currentMediaId == epoch.targetMediaId
    }

    @Synchronized
    fun complete(
        requestId: Long,
        family: DirectDsdTrackTransportFamily,
    ): Boolean {
        val epoch = active ?: return false
        if (epoch.requestId != requestId) return false
        if (epoch.targetFacts?.family != family) return false
        if (currentMediaId != epoch.targetMediaId) return false
        active = null
        milestone("navigationTransition=completed request=$requestId family=$family")
        return true
    }

    @Synchronized
    fun cancel(requestId: Long, reason: String): Boolean {
        if (active?.requestId != requestId) return false
        active = null
        milestone("navigationTransition=cancelled request=$requestId reason=$reason")
        return true
    }

    @Synchronized
    fun abort(reason: String) {
        val epoch = active ?: return
        active = null
        milestone("navigationTransition=aborted request=${epoch.requestId} reason=$reason")
    }

    @Synchronized
    fun snapshot(): ManualNavigationTransitionEpoch? = active

    @Synchronized
    fun isTargetCurrent(requestId: Long): Boolean {
        val epoch = active ?: return false
        return epoch.requestId == requestId && currentMediaId == epoch.targetMediaId
    }

    private fun bindDestination(
        facts: ManualNavigationDestinationFacts,
    ): ManualNavigationTransitionEpoch? {
        val epoch = active ?: return null
        if (currentMediaId != epoch.targetMediaId) return null
        val bound = epoch.copy(targetFacts = facts)
        active = bound
        milestone(
            "navigationTransition=destination-bound request=${bound.requestId} " +
                "family=${facts.family} rate=${facts.sampleRateHz} channels=${facts.channelCount}",
        )
        return bound
    }

    private companion object {
        const val TAG = "MicaDsdTransition"
    }
}
