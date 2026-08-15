package com.mica.music.media.dsd

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.MediaSource
import com.mica.music.media.dsf.DsfExtractorPacketFacts

data class ManualNavigationDestinationFacts(
    val family: DirectDsdTrackTransportFamily,
    val sampleRateHz: Int,
    val channelCount: Int,
    val formatIdentity: String,
)

data class ManualNavigationPlaybackIdentity(
    val periodUid: Any,
    val windowSequenceNumber: Long,
) {
    companion object {
        fun from(mediaPeriodId: MediaSource.MediaPeriodId): ManualNavigationPlaybackIdentity =
            ManualNavigationPlaybackIdentity(
                periodUid = mediaPeriodId.periodUid,
                windowSequenceNumber = mediaPeriodId.windowSequenceNumber,
            )
    }
}

data class ManualNavigationTransitionEpoch(
    val requestId: Long,
    val targetMediaId: String,
    val requestedPlaying: Boolean,
    val sourceFamily: DirectDsdTrackTransportFamily,
    val sourcePlaybackIdentity: ManualNavigationPlaybackIdentity? = null,
    val expectedTargetPeriodUid: Any? = null,
    val targetPlaybackIdentity: ManualNavigationPlaybackIdentity? = null,
    val targetFacts: ManualNavigationDestinationFacts? = null,
)

object ManualNavigationTimelinePeriodResolver {
    fun resolveSinglePeriodUid(
        timeline: Timeline,
        windowIndex: Int,
        expectedMediaId: String,
    ): Any? {
        if (windowIndex !in 0 until timeline.windowCount) return null
        val window = timeline.getWindow(windowIndex, Timeline.Window())
        if (window.mediaItem.mediaId != expectedMediaId) return null
        if (window.firstPeriodIndex != window.lastPeriodIndex) return null
        val period = timeline.getPeriod(window.firstPeriodIndex, Timeline.Period(), true)
        if (period.windowIndex != windowIndex || period.isPlaceholder) return null
        return period.uid
    }
}

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
    private var currentApplicationPeriodUid: Any? = null
    private var authoritativeSourcePlaybackIdentity: ManualNavigationPlaybackIdentity? = null

    @Synchronized
    fun updateApplicationCurrentness(mediaId: String?, currentPeriodUid: Any?) {
        val previousMediaId = currentMediaId
        currentMediaId = mediaId
        currentApplicationPeriodUid = currentPeriodUid
        authoritativeSourcePlaybackIdentity?.let { source ->
            if (currentPeriodUid == null || source.periodUid != currentPeriodUid) {
                authoritativeSourcePlaybackIdentity = null
                milestone("navigationTransition=source-occurrence-cleared reason=application-period-changed")
            }
        }
        val epoch = active
        if (
            epoch != null &&
            epoch.targetMediaId == mediaId &&
            epoch.expectedTargetPeriodUid == null &&
            currentPeriodUid != null
        ) {
            active = epoch.copy(expectedTargetPeriodUid = currentPeriodUid)
            milestone(
                "navigationTransition=target-period-resolved request=${epoch.requestId} " +
                    "target=${epoch.targetMediaId}",
            )
        }
        if (previousMediaId != mediaId) {
            milestone("navigationTransition=current-media target=${mediaId ?: "none"}")
        }
    }

    @Synchronized
    fun publish(
        targetMediaId: String,
        requestedPlaying: Boolean,
        sourceFamily: DirectDsdTrackTransportFamily,
        expectedTargetPeriodUid: Any? = null,
    ): ManualNavigationTransitionEpoch {
        require(targetMediaId.isNotBlank())
        val previous = active
        val epoch = ManualNavigationTransitionEpoch(
            requestId = ++nextRequestId,
            targetMediaId = targetMediaId,
            requestedPlaying = requestedPlaying,
            sourceFamily = sourceFamily,
            sourcePlaybackIdentity = authoritativeSourcePlaybackIdentity,
            expectedTargetPeriodUid = expectedTargetPeriodUid,
        )
        active = epoch
        milestone(
            "navigationTransition=published request=${epoch.requestId} target=${epoch.targetMediaId} " +
                "playing=${epoch.requestedPlaying} source=${epoch.sourceFamily} " +
                "targetPeriodKnown=${epoch.expectedTargetPeriodUid != null} " +
                "superseded=${previous?.requestId ?: -1L}",
        )
        return epoch
    }

    @Synchronized
    fun observePlaybackStream(mediaPeriodId: MediaSource.MediaPeriodId): ManualNavigationPlaybackIdentity {
        val identity = ManualNavigationPlaybackIdentity.from(mediaPeriodId)
        val currentPeriodUid = currentApplicationPeriodUid
        if (currentPeriodUid != null && identity.periodUid == currentPeriodUid) {
            authoritativeSourcePlaybackIdentity = identity
            milestone(
                "navigationTransition=source-occurrence-authoritative " +
                    "windowSequence=${identity.windowSequenceNumber}",
            )
        }
        return identity
    }

    @Synchronized
    fun observeDirectRetirementStop(): ManualNavigationTransitionEpoch? {
        val epoch = active ?: return null
        if (epoch.sourceFamily != DirectDsdTrackTransportFamily.DOP) return null
        milestone("navigationTransition=direct-retirement-stop request=${epoch.requestId}")
        return epoch
    }

    @Synchronized
    fun bindDirectDestination(
        facts: DsfExtractorPacketFacts,
        playbackIdentity: ManualNavigationPlaybackIdentity,
    ): ManualNavigationTransitionEpoch? = bindDestination(
        ManualNavigationDestinationFacts(
            family = DirectDsdTrackTransportFamily.DOP,
            sampleRateHz = facts.sourceSampleRateHz,
            channelCount = facts.channelCount,
            formatIdentity = "dsf:${facts.sourceSampleRateHz}:${facts.channelCount}:${facts.sourceBitOrder}",
        ),
        playbackIdentity,
    )

    @Synchronized
    fun bindPcmDestination(
        format: Format,
        playbackIdentity: ManualNavigationPlaybackIdentity?,
    ): ManualNavigationTransitionEpoch? {
        if (playbackIdentity == null) return null
        return bindDestination(
            ManualNavigationDestinationFacts(
                family = DirectDsdTrackTransportFamily.PCM,
                sampleRateHz = format.sampleRate,
                channelCount = format.channelCount,
                formatIdentity = format.sampleMimeType ?: format.codecs ?: "pcm",
            ),
            playbackIdentity,
        )
    }

    @Synchronized
    fun isCurrentDestination(
        requestId: Long,
        facts: ManualNavigationDestinationFacts,
        playbackIdentity: ManualNavigationPlaybackIdentity,
    ): Boolean {
        val epoch = active ?: return false
        return epoch.requestId == requestId &&
            epoch.targetFacts == facts &&
            epoch.targetPlaybackIdentity == playbackIdentity &&
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
        if (epoch.targetPlaybackIdentity == null) return false
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

    private fun bindDestination(
        facts: ManualNavigationDestinationFacts,
        playbackIdentity: ManualNavigationPlaybackIdentity,
    ): ManualNavigationTransitionEpoch? {
        val epoch = active ?: return null
        if (currentMediaId != epoch.targetMediaId) return null
        val expectedPeriodUid = epoch.expectedTargetPeriodUid ?: return null
        if (expectedPeriodUid != playbackIdentity.periodUid) return null
        if (epoch.sourcePlaybackIdentity == playbackIdentity) return null
        val targetPlaybackIdentity = epoch.targetPlaybackIdentity
        if (targetPlaybackIdentity != null && targetPlaybackIdentity != playbackIdentity) return null
        if (epoch.targetFacts != null && epoch.targetFacts != facts) return null
        val bound = epoch.copy(
            targetPlaybackIdentity = targetPlaybackIdentity ?: playbackIdentity,
            targetFacts = facts,
        )
        active = bound
        milestone(
            "navigationTransition=destination-bound request=${bound.requestId} " +
                "family=${facts.family} rate=${facts.sampleRateHz} channels=${facts.channelCount} " +
                "windowSequence=${playbackIdentity.windowSequenceNumber}",
        )
        return bound
    }

    private companion object {
        const val TAG = "MicaDsdTransition"
    }
}
