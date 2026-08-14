package com.mica.music.media.dsd

import com.mica.music.media.dsf.DsfExtractorPacketCanonicalizer
import com.mica.music.media.dsf.DsfExtractorPacketFacts

data class DirectDsdTransportWriteResult(
    val canonicalBytesConsumed: Int,
)

data class DirectDsdRetainedSourceTransitionResult(
    val feederPendingZero: Boolean,
    val sourceResetApplied: Boolean,
)

data class DirectDsdFreshTransitionPreparationResult(
    val feederPendingZero: Boolean,
    val carrierResetApplied: Boolean,
)

interface DirectDsdTransportSession : AutoCloseable {
    val facts: DsfExtractorPacketFacts
    val startupPrefillReady: Boolean
    val playbackArmed: Boolean

    fun writeCanonical(
        bytes: ByteArray,
        offset: Int,
        byteCount: Int,
    ): DirectDsdTransportWriteResult

    fun armPlayback()

    /** Starts chronology-safe carrier liveness while Media3 is logically paused. */
    fun startPauseGapLiveness()

    /** Stops and joins pause carrier liveness before Media3 resumes source delivery. */
    fun stopPauseGapLiveness()

    /**
     * Rebuild-only quiesce seam. Returns true only when an active/failed pause GAP was stopped and
     * joined; CONTENT/never-started state is a side-effect-free false result.
     */
    fun quiescePauseGapForOutputRebuild(): Boolean

    /** Drains already-accepted carrier and resets only source generation on one retained DoP plan. */
    fun transitionRetainedSource(newFacts: DsfExtractorPacketFacts): DirectDsdRetainedSourceTransitionResult

    /** Drains old accepted output and terminates all P5 carrier/source state before fresh runtime. */
    fun prepareFreshTrackTransition(
        reason: DoPCarrierSessionReset,
    ): DirectDsdFreshTransitionPreparationResult

    /** Returns true only when end-of-stream state is clean and transport can finish. */
    fun finishEndOfStream(): Boolean
}

fun interface DirectDsdTransportSessionFactory {
    fun open(facts: DsfExtractorPacketFacts): DirectDsdTransportSession
}

data class DirectDsdRendererPumpSnapshot(
    val pendingCanonicalBytes: Int,
    val offeredPacketCount: Long,
    val fullyConsumedPacketCount: Long,
    val canonicalBytesCommitted: Long,
    val lastFullyConsumedPacketTimeUs: Long?,
    val inputEnded: Boolean,
)

/**
 * Renderer-side chronology owner between Media3 extractor packets and the Direct DSD transport.
 * A packet is canonicalized exactly once. Unconsumed canonical bytes remain owned here until the
 * transport reports them consumed; a later extractor packet cannot overtake that tail.
 */
class DirectDsdRendererPump(
    facts: DsfExtractorPacketFacts,
    private val session: DirectDsdTransportSession,
) : AutoCloseable {
    var facts: DsfExtractorPacketFacts = facts
        private set
    private var pending = ByteArray(0)
    private var pendingOffset = 0
    private var pendingPacketTimeUs: Long? = null
    private var offeredPackets = 0L
    private var consumedPackets = 0L
    private var committedBytes = 0L
    private var lastConsumedTimeUs: Long? = null
    private var ended = false
    private var closed = false

    init {
        require(session.facts == facts) { "transport facts differ from extractor facts" }
    }

    fun canAcceptPacket(): Boolean = !closed && !ended && pendingBytes() == 0

    fun offerExtractorPacket(packet: ByteArray, timeUs: Long) {
        check(canAcceptPacket()) { "previous DSD packet is still pending" }
        pending = DsfExtractorPacketCanonicalizer.canonicalize(packet, facts = facts)
        pendingOffset = 0
        pendingPacketTimeUs = timeUs
        offeredPackets++
        if (pending.isEmpty()) completePendingPacket()
    }

    fun pump(): DirectDsdTransportWriteResult {
        check(!closed) { "pump after close" }
        val remaining = pendingBytes()
        if (remaining == 0) return DirectDsdTransportWriteResult(0)
        val result = session.writeCanonical(pending, pendingOffset, remaining)
        require(result.canonicalBytesConsumed in 0..remaining) {
            "transport consumed ${result.canonicalBytesConsumed} of $remaining canonical bytes"
        }
        if (result.canonicalBytesConsumed > 0) {
            pendingOffset += result.canonicalBytesConsumed
            committedBytes += result.canonicalBytesConsumed.toLong()
            if (pendingBytes() == 0) completePendingPacket()
        }
        return result
    }

    fun signalEndOfStream(): Boolean {
        check(!closed) { "end after close" }
        if (pendingBytes() != 0) return false
        if (!session.finishEndOfStream()) return false
        ended = true
        return true
    }

    fun isEnded(): Boolean = ended

    fun isStartupPrefillReady(): Boolean = session.startupPrefillReady

    fun isPlaybackArmed(): Boolean = session.playbackArmed

    fun armPlayback() {
        check(!closed) { "arm after pump close" }
        session.armPlayback()
    }

    fun startPauseGapLiveness() {
        check(!closed) { "pause-gap start after pump close" }
        session.startPauseGapLiveness()
    }

    fun stopPauseGapLiveness() {
        check(!closed) { "pause-gap stop after pump close" }
        session.stopPauseGapLiveness()
    }

    fun quiescePauseGapForOutputRebuild(): Boolean {
        if (closed) return false
        return session.quiescePauseGapForOutputRebuild()
    }

    /**
     * Track boundary for one retained carrier plan. Renderer-owned uncommitted canonical bytes are
     * discarded; only bytes already accepted by feeder/P5 are drained by the transport session.
     */
    fun transitionRetainedSource(newFacts: DsfExtractorPacketFacts): Pair<Int, DirectDsdRetainedSourceTransitionResult> {
        check(!closed) { "source transition after close" }
        val discardedCanonicalBytes = pendingBytes()
        pending = ByteArray(0)
        pendingOffset = 0
        pendingPacketTimeUs = null
        val result = session.transitionRetainedSource(newFacts)
        check(result.feederPendingZero && result.sourceResetApplied) {
            "retained Direct DSD source transition did not reach pending-zero reset"
        }
        facts = newFacts
        offeredPackets = 0L
        consumedPackets = 0L
        committedBytes = 0L
        lastConsumedTimeUs = null
        ended = false
        return discardedCanonicalBytes to result
    }

    fun prepareFreshTrackTransition(
        reason: DoPCarrierSessionReset,
    ): Pair<Int, DirectDsdFreshTransitionPreparationResult> {
        check(!closed) { "fresh transition after close" }
        val discardedCanonicalBytes = pendingBytes()
        pending = ByteArray(0)
        pendingOffset = 0
        pendingPacketTimeUs = null
        val result = session.prepareFreshTrackTransition(reason)
        check(result.feederPendingZero && result.carrierResetApplied) {
            "fresh Direct DSD transition did not reach reset/pending-zero"
        }
        return discardedCanonicalBytes to result
    }

    fun snapshot(): DirectDsdRendererPumpSnapshot = DirectDsdRendererPumpSnapshot(
        pendingCanonicalBytes = pendingBytes(),
        offeredPacketCount = offeredPackets,
        fullyConsumedPacketCount = consumedPackets,
        canonicalBytesCommitted = committedBytes,
        lastFullyConsumedPacketTimeUs = lastConsumedTimeUs,
        inputEnded = ended,
    )

    override fun close() {
        if (closed) return
        closed = true
        session.close()
    }

    private fun pendingBytes(): Int = pending.size - pendingOffset

    private fun completePendingPacket() {
        lastConsumedTimeUs = pendingPacketTimeUs
        pendingPacketTimeUs = null
        pending = ByteArray(0)
        pendingOffset = 0
        consumedPackets++
    }
}
