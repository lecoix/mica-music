package com.mica.music.media.dsd

enum class DoPDiscontinuity {
    NEW_SOURCE_GENERATION,
    SEEK,
}

enum class DoPCarrierSessionReset {
    NEW_CARRIER_SESSION,
    RECONFIGURE,
}

private enum class DoPPackedFrameKind {
    CONTENT,
    IDLE,
}

data class DoPPipelineWriteResult(
    val canonicalFramesConsumed: Int,
    val carrierBytesEmitted: Int,
    val runtimeFramesPacked: Int,
    val runtimeFramesFullyEmitted: Int,
)

data class DoPPipelineByteWriteResult(
    val canonicalBytesConsumed: Int,
    val canonicalFramesCompleted: Int,
    val carrierBytesEmitted: Int,
    val runtimeFramesPacked: Int,
    val runtimeFramesFullyEmitted: Int,
)

data class DoPPipelineDrainResult(
    val carrierBytesEmitted: Int,
    val runtimeFramesPacked: Int,
    val runtimeFramesFullyEmitted: Int,
    val completedPendingHalfFrameWithIdle: Boolean,
)

data class DoPPipelineIdleWriteResult(
    val idleFramesConsumed: Int,
    val carrierBytesEmitted: Int,
    val runtimeFramesPacked: Int,
    val runtimeFramesFullyEmitted: Int,
)

enum class DoPGapBlockedReason {
    PARTIAL_CANONICAL_FRAME,
}

data class DoPCarrierGapWriteResult(
    val requestedGapFrames: Int,
    val gapFramesAccepted: Int,
    val completedPendingHalfFrameWithIdle: Boolean,
    val pureIdleFramesPacked: Int,
    val carrierBytesEmitted: Int,
    val runtimeFramesFullyEmitted: Int,
    val blockedReason: DoPGapBlockedReason?,
) {
    init {
        require(requestedGapFrames >= 0)
        require(gapFramesAccepted >= 0 && gapFramesAccepted <= requestedGapFrames)
        require(pureIdleFramesPacked >= 0 && pureIdleFramesPacked <= gapFramesAccepted)
        require(
            gapFramesAccepted == pureIdleFramesPacked + if (completedPendingHalfFrameWithIdle) 1 else 0,
        )
        require(carrierBytesEmitted >= 0)
        require(runtimeFramesFullyEmitted >= 0)
    }
}

data class DoPPipelineFlushResult(
    val carrierBytesEmitted: Int,
    val runtimeFramesFullyEmitted: Int,
)

data class DoPPipelineDiscontinuityResult(
    val reason: DoPDiscontinuity,
    val discardedPartialCanonicalFrameBytes: Int,
    val discardedPendingCanonicalHalfFrame: Boolean,
    val discardedPackedCarrierBytes: Int,
)

data class DoPSourceResetResult(
    val reason: DoPDiscontinuity,
    val discardedPartialCanonicalFrameBytes: Int,
    val discardedPendingCanonicalHalfFrame: Boolean,
    val discardedCanonicalSourceBytes: Int,
    val discardedPackedCarrierBytes: Int,
    val markerBeforeReset: Int,
    val markerAfterReset: Int,
)

data class DoPCarrierSessionResetResult(
    val reason: DoPCarrierSessionReset,
    val discardedPartialCanonicalFrameBytes: Int,
    val discardedPendingCanonicalHalfFrame: Boolean,
    val discardedCanonicalSourceBytes: Int,
    val discardedPackedCarrierBytes: Int,
    val markerBeforeReset: Int,
    val markerAfterReset: Int,
)

data class DoPPipelineAccounting(
    val canonicalBytesConsumed: Long,
    val canonicalFramesConsumed: Long,
    val runtimeFramesPacked: Long,
    val runtimeFramesFullyEmitted: Long,
    val carrierBytesEmitted: Long,
    val carrierBytesDiscardedAtDiscontinuity: Long,
    val contentRuntimeFramesPacked: Long,
    val idleRuntimeFramesPacked: Long,
    val contentCarrierBytesEmitted: Long,
    val idleCarrierBytesEmitted: Long,
    val canonicalSourceBytesDiscardedAtReset: Long,
    val pendingPackedCarrierBytes: Int,
    val pendingPartialCanonicalFrameBytes: Int,
    val hasPendingCanonicalHalfFrame: Boolean,
    val lastPackedMarker: Int?,
    val nextMarker: Int,
) {
    init {
        require(canonicalBytesConsumed >= 0L)
        require(canonicalFramesConsumed >= 0L)
        require(runtimeFramesPacked >= 0L)
        require(runtimeFramesFullyEmitted >= 0L)
        require(carrierBytesEmitted >= 0L)
        require(carrierBytesDiscardedAtDiscontinuity >= 0L)
        require(contentRuntimeFramesPacked >= 0L)
        require(idleRuntimeFramesPacked >= 0L)
        require(contentCarrierBytesEmitted >= 0L)
        require(idleCarrierBytesEmitted >= 0L)
        require(canonicalSourceBytesDiscardedAtReset >= 0L)
        require(pendingPackedCarrierBytes >= 0)
        require(pendingPartialCanonicalFrameBytes >= 0)
        require(lastPackedMarker == null || lastPackedMarker == DoPEncoder.MARKER_A || lastPackedMarker == DoPEncoder.MARKER_B)
        require(nextMarker == DoPEncoder.MARKER_A || nextMarker == DoPEncoder.MARKER_B)
    }
}

/**
 * Pure P5 DoP runtime-pipeline preparation seam.
 *
 * The pipeline consumes canonical DSD bytes, assembles complete channel-byte frames, preserves
 * [DoPEncoder] half-frame carry/marker state, packs exactly one DoP runtime frame at a time, and
 * exposes arbitrary byte fragments to a future transport adapter. It owns no Android/USB/session
 * lifecycle policy.
 *
 * A discontinuity is an explicit stream boundary: bytes returned before reset belong to the old
 * stream segment. Stale bytes still buffered inside this object are discarded by reset.
 */
class DoPRuntimePipeline(
    val plan: DoPCarrierPlan,
) {
    init {
        validatePlan(plan)
    }

    private var encoder = DoPEncoder(channelCount = plan.channelCount)
    private val partialCanonicalFrame = ByteArray(plan.channelCount)
    private var partialCanonicalFrameBytes = 0
    private val words = IntArray(plan.channelCount)
    private val packedRuntimeFrame = ByteArray(plan.bytesPerRuntimeFrame)
    private var pendingPackedOffset = 0
    private var pendingPackedLength = 0
    private var pendingPackedKind: DoPPackedFrameKind? = null

    private var totalCanonicalBytesConsumed = 0L
    private var totalCanonicalFramesConsumed = 0L
    private var totalRuntimeFramesPacked = 0L
    private var totalRuntimeFramesFullyEmitted = 0L
    private var totalCarrierBytesEmitted = 0L
    private var totalCarrierBytesDiscardedAtDiscontinuity = 0L
    private var totalContentRuntimeFramesPacked = 0L
    private var totalIdleRuntimeFramesPacked = 0L
    private var totalContentCarrierBytesEmitted = 0L
    private var totalIdleCarrierBytesEmitted = 0L
    private var totalCanonicalSourceBytesDiscardedAtReset = 0L
    private var lastPackedMarker: Int? = null

    /**
     * Frame-oriented convenience wrapper. It may only start when no partial canonical channel-frame
     * is buffered from [writeBytes].
     */
    fun write(
        source: ByteArray,
        sourceOffset: Int = 0,
        frameCount: Int,
        destination: ByteArray,
        destinationOffset: Int = 0,
        destinationByteCount: Int = destination.size - destinationOffset,
    ): DoPPipelineWriteResult {
        require(partialCanonicalFrameBytes == 0) {
            "frame-oriented write cannot start while a partial canonical frame is buffered"
        }
        require(sourceOffset >= 0 && frameCount >= 0 && sourceOffset <= source.size)
        require(frameCount <= (source.size - sourceOffset) / plan.channelCount) {
            "canonical DSD frame range out of bounds"
        }
        require(frameCount <= Int.MAX_VALUE / plan.channelCount) {
            "canonical DSD frame byte count overflows"
        }

        val byteResult = writeBytes(
            source = source,
            sourceOffset = sourceOffset,
            sourceByteCount = frameCount * plan.channelCount,
            destination = destination,
            destinationOffset = destinationOffset,
            destinationByteCount = destinationByteCount,
        )
        check(byteResult.canonicalBytesConsumed % plan.channelCount == 0)
        return DoPPipelineWriteResult(
            canonicalFramesConsumed = byteResult.canonicalBytesConsumed / plan.channelCount,
            carrierBytesEmitted = byteResult.carrierBytesEmitted,
            runtimeFramesPacked = byteResult.runtimeFramesPacked,
            runtimeFramesFullyEmitted = byteResult.runtimeFramesFullyEmitted,
        )
    }

    /**
     * Byte-oriented input seam for arbitrary source chunk boundaries, including one-byte chunks.
     * Output capacity may also be smaller than one runtime frame.
     *
     * The object buffers at most `channelCount - 1` canonical bytes, one [DoPEncoder] half-frame,
     * and one packed DoP runtime frame.
     */
    fun writeBytes(
        source: ByteArray,
        sourceOffset: Int = 0,
        sourceByteCount: Int = source.size - sourceOffset,
        destination: ByteArray,
        destinationOffset: Int = 0,
        destinationByteCount: Int = destination.size - destinationOffset,
    ): DoPPipelineByteWriteResult {
        require(sourceOffset >= 0 && sourceByteCount >= 0 && sourceOffset <= source.size)
        require(sourceByteCount <= source.size - sourceOffset)
        require(destinationOffset >= 0 && destinationByteCount >= 0 && destinationOffset <= destination.size)
        require(destinationByteCount <= destination.size - destinationOffset)

        if (destinationByteCount == 0) {
            return DoPPipelineByteWriteResult(0, 0, 0, 0, 0)
        }

        var sourceBytesConsumed = 0
        var completedCanonicalFrames = 0
        var emittedBytes = 0
        var packedFrames = 0
        var fullyEmittedFrames = 0
        var outputOffset = destinationOffset
        var outputRemaining = destinationByteCount

        val initialFlush = emitPending(destination, outputOffset, outputRemaining)
        emittedBytes += initialFlush.bytes
        fullyEmittedFrames += initialFlush.framesCompleted
        outputOffset += initialFlush.bytes
        outputRemaining -= initialFlush.bytes

        while (sourceBytesConsumed < sourceByteCount && outputRemaining > 0) {
            check(pendingPackedBytes() == 0) {
                "pending packed carrier bytes must be drained before consuming more canonical input"
            }

            val needed = plan.channelCount - partialCanonicalFrameBytes
            val available = sourceByteCount - sourceBytesConsumed
            val copied = minOf(needed, available)
            source.copyInto(
                destination = partialCanonicalFrame,
                destinationOffset = partialCanonicalFrameBytes,
                startIndex = sourceOffset + sourceBytesConsumed,
                endIndex = sourceOffset + sourceBytesConsumed + copied,
            )
            partialCanonicalFrameBytes += copied
            sourceBytesConsumed += copied
            totalCanonicalBytesConsumed += copied.toLong()

            if (partialCanonicalFrameBytes < plan.channelCount) continue

            val produced = encoder.encodeFrames(
                source = partialCanonicalFrame,
                frameCount = 1,
                destinationWords = words,
            )
            partialCanonicalFrameBytes = 0
            completedCanonicalFrames++
            totalCanonicalFramesConsumed++

            if (produced == 0) continue
            check(produced == 1)
            packCurrentRuntimeFrame(DoPPackedFrameKind.CONTENT)
            packedFrames++

            val flush = emitPending(destination, outputOffset, outputRemaining)
            emittedBytes += flush.bytes
            fullyEmittedFrames += flush.framesCompleted
            outputOffset += flush.bytes
            outputRemaining -= flush.bytes
        }

        return DoPPipelineByteWriteResult(
            canonicalBytesConsumed = sourceBytesConsumed,
            canonicalFramesCompleted = completedCanonicalFrames,
            carrierBytesEmitted = emittedBytes,
            runtimeFramesPacked = packedFrames,
            runtimeFramesFullyEmitted = fullyEmittedFrames,
        )
    }

    /**
     * End-of-source finalization only. A pending DoP half-frame may be completed once using the
     * existing DSD idle byte. Missing bytes inside a canonical channel-frame are never synthesized.
     */
    fun drainEndOfSource(
        destination: ByteArray,
        destinationOffset: Int = 0,
        destinationByteCount: Int = destination.size - destinationOffset,
    ): DoPPipelineDrainResult {
        require(destinationOffset >= 0)
        require(destinationByteCount >= 0)
        require(destinationOffset <= destination.size)
        require(destinationByteCount <= destination.size - destinationOffset)
        require(partialCanonicalFrameBytes == 0) {
            "end-of-source reached with an incomplete canonical channel-byte frame"
        }

        if (destinationByteCount == 0) {
            return DoPPipelineDrainResult(0, 0, 0, completedPendingHalfFrameWithIdle = false)
        }

        var emittedBytes = 0
        var packedFrames = 0
        var fullyEmittedFrames = 0
        var completedWithIdle = false
        var outputOffset = destinationOffset
        var outputRemaining = destinationByteCount

        val initialFlush = emitPending(destination, outputOffset, outputRemaining)
        emittedBytes += initialFlush.bytes
        fullyEmittedFrames += initialFlush.framesCompleted
        outputOffset += initialFlush.bytes
        outputRemaining -= initialFlush.bytes

        if (outputRemaining > 0 && pendingPackedBytes() == 0 && encoder.hasPendingHalfFrame()) {
            val produced = encoder.drain(words)
            check(produced == 1)
            packCurrentRuntimeFrame(DoPPackedFrameKind.CONTENT)
            packedFrames++
            completedWithIdle = true

            val flush = emitPending(destination, outputOffset, outputRemaining)
            emittedBytes += flush.bytes
            fullyEmittedFrames += flush.framesCompleted
        }

        return DoPPipelineDrainResult(
            carrierBytesEmitted = emittedBytes,
            runtimeFramesPacked = packedFrames,
            runtimeFramesFullyEmitted = fullyEmittedFrames,
            completedPendingHalfFrameWithIdle = completedWithIdle,
        )
    }

    /**
     * Low-level pure-idle primitive. This intentionally does not resolve pending source chronology;
     * session/future feeder code must use [writeGapFrames] through [DoPCarrierSession] instead.
     */
    internal fun writeIdleFrames(
        frameCount: Int,
        destination: ByteArray,
        destinationOffset: Int = 0,
        destinationByteCount: Int = destination.size - destinationOffset,
    ): DoPPipelineIdleWriteResult {
        require(frameCount >= 0)
        require(destinationOffset >= 0 && destinationByteCount >= 0 && destinationOffset <= destination.size)
        require(destinationByteCount <= destination.size - destinationOffset)

        if (destinationByteCount == 0) {
            return DoPPipelineIdleWriteResult(0, 0, 0, 0)
        }

        var idleFramesConsumed = 0
        var emittedBytes = 0
        var packedFrames = 0
        var fullyEmittedFrames = 0
        var outputOffset = destinationOffset
        var outputRemaining = destinationByteCount

        val initialFlush = emitPending(destination, outputOffset, outputRemaining)
        emittedBytes += initialFlush.bytes
        fullyEmittedFrames += initialFlush.framesCompleted
        outputOffset += initialFlush.bytes
        outputRemaining -= initialFlush.bytes

        while (idleFramesConsumed < frameCount && outputRemaining > 0) {
            check(pendingPackedBytes() == 0)
            check(encoder.encodeIdleFrame(words) == 1)
            packCurrentRuntimeFrame(DoPPackedFrameKind.IDLE)
            idleFramesConsumed++
            packedFrames++

            val flush = emitPending(destination, outputOffset, outputRemaining)
            emittedBytes += flush.bytes
            fullyEmittedFrames += flush.framesCompleted
            outputOffset += flush.bytes
            outputRemaining -= flush.bytes
        }

        return DoPPipelineIdleWriteResult(
            idleFramesConsumed = idleFramesConsumed,
            carrierBytesEmitted = emittedBytes,
            runtimeFramesPacked = packedFrames,
            runtimeFramesFullyEmitted = fullyEmittedFrames,
        )
    }

    /**
     * Chronology-safe carrier-gap operation for the session facade.
     *
     * A gap may not overtake canonical DSD bytes already consumed from the source. Existing packed
     * output is therefore emitted first. A complete canonical frame retained by [DoPEncoder] as a
     * pending half-frame is then completed with one `0x69` byte per channel and remains CONTENT
     * accounting. Only the remaining requested gap budget is packed as pure `0x69/0x69` IDLE.
     *
     * An incomplete multichannel canonical frame cannot be completed without inventing source
     * bytes, so gap entry is blocked before output or marker state changes. A zero-frame request is
     * a strict no-op, including when packed output is pending.
     */
    internal fun writeGapFrames(
        frameCount: Int,
        destination: ByteArray,
        destinationOffset: Int = 0,
        destinationByteCount: Int = destination.size - destinationOffset,
    ): DoPCarrierGapWriteResult {
        require(frameCount >= 0)
        require(destinationOffset >= 0 && destinationByteCount >= 0 && destinationOffset <= destination.size)
        require(destinationByteCount <= destination.size - destinationOffset)

        if (frameCount == 0) {
            return DoPCarrierGapWriteResult(
                requestedGapFrames = 0,
                gapFramesAccepted = 0,
                completedPendingHalfFrameWithIdle = false,
                pureIdleFramesPacked = 0,
                carrierBytesEmitted = 0,
                runtimeFramesFullyEmitted = 0,
                blockedReason = null,
            )
        }

        if (partialCanonicalFrameBytes > 0) {
            check(pendingPackedBytes() == 0) {
                "partial canonical input and packed carrier output must not coexist"
            }
            return DoPCarrierGapWriteResult(
                requestedGapFrames = frameCount,
                gapFramesAccepted = 0,
                completedPendingHalfFrameWithIdle = false,
                pureIdleFramesPacked = 0,
                carrierBytesEmitted = 0,
                runtimeFramesFullyEmitted = 0,
                blockedReason = DoPGapBlockedReason.PARTIAL_CANONICAL_FRAME,
            )
        }

        if (destinationByteCount == 0) {
            return DoPCarrierGapWriteResult(
                requestedGapFrames = frameCount,
                gapFramesAccepted = 0,
                completedPendingHalfFrameWithIdle = false,
                pureIdleFramesPacked = 0,
                carrierBytesEmitted = 0,
                runtimeFramesFullyEmitted = 0,
                blockedReason = null,
            )
        }

        var gapFramesAccepted = 0
        var completedPendingHalfFrameWithIdle = false
        var pureIdleFramesPacked = 0
        var emittedBytes = 0
        var fullyEmittedFrames = 0
        var outputOffset = destinationOffset
        var outputRemaining = destinationByteCount

        val initialFlush = emitPending(destination, outputOffset, outputRemaining)
        emittedBytes += initialFlush.bytes
        fullyEmittedFrames += initialFlush.framesCompleted
        outputOffset += initialFlush.bytes
        outputRemaining -= initialFlush.bytes

        while (gapFramesAccepted < frameCount && outputRemaining > 0) {
            check(pendingPackedBytes() == 0) {
                "pending packed carrier bytes must be emitted before accepting a new gap frame"
            }

            if (encoder.hasPendingHalfFrame()) {
                check(!completedPendingHalfFrameWithIdle) {
                    "one gap request cannot complete more than one pending content half-frame"
                }
                check(encoder.drain(words) == 1)
                packCurrentRuntimeFrame(DoPPackedFrameKind.CONTENT)
                completedPendingHalfFrameWithIdle = true
            } else {
                check(encoder.encodeIdleFrame(words) == 1)
                packCurrentRuntimeFrame(DoPPackedFrameKind.IDLE)
                pureIdleFramesPacked++
            }
            gapFramesAccepted++

            val flush = emitPending(destination, outputOffset, outputRemaining)
            emittedBytes += flush.bytes
            fullyEmittedFrames += flush.framesCompleted
            outputOffset += flush.bytes
            outputRemaining -= flush.bytes
        }

        return DoPCarrierGapWriteResult(
            requestedGapFrames = frameCount,
            gapFramesAccepted = gapFramesAccepted,
            completedPendingHalfFrameWithIdle = completedPendingHalfFrameWithIdle,
            pureIdleFramesPacked = pureIdleFramesPacked,
            carrierBytesEmitted = emittedBytes,
            runtimeFramesFullyEmitted = fullyEmittedFrames,
            blockedReason = null,
        )
    }

    /** Emits only bytes from an already-packed runtime frame; never packs or advances a marker. */
    internal fun flushPendingOutput(
        destination: ByteArray,
        destinationOffset: Int = 0,
        destinationByteCount: Int = destination.size - destinationOffset,
    ): DoPPipelineFlushResult {
        require(destinationOffset >= 0 && destinationByteCount >= 0 && destinationOffset <= destination.size)
        require(destinationByteCount <= destination.size - destinationOffset)
        val emitted = emitPending(destination, destinationOffset, destinationByteCount)
        return DoPPipelineFlushResult(
            carrierBytesEmitted = emitted.bytes,
            runtimeFramesFullyEmitted = emitted.framesCompleted,
        )
    }

    /**
     * Source-only reset for a retained carrier session. Stale source state is discarded while the
     * next DoP marker phase is preserved exactly.
     */
    fun resetSourceForRetainedCarrier(reason: DoPDiscontinuity): DoPSourceResetResult {
        val marker = encoder.marker
        val discardedPartialFrameBytes = partialCanonicalFrameBytes
        val discardedHalfFrame = encoder.hasPendingHalfFrame()
        val discardedSourceBytes = discardedPartialFrameBytes + if (discardedHalfFrame) plan.channelCount else 0
        val discardedPackedBytes = if (pendingPackedKind == DoPPackedFrameKind.CONTENT) pendingPackedBytes() else 0
        totalCanonicalSourceBytesDiscardedAtReset += discardedSourceBytes.toLong()
        totalCarrierBytesDiscardedAtDiscontinuity += discardedPackedBytes.toLong()
        encoder = DoPEncoder(channelCount = plan.channelCount, initialMarker = marker)
        partialCanonicalFrameBytes = 0
        if (pendingPackedKind == DoPPackedFrameKind.CONTENT) {
            clearPendingPackedState()
        }
        return DoPSourceResetResult(
            reason = reason,
            discardedPartialCanonicalFrameBytes = discardedPartialFrameBytes,
            discardedPendingCanonicalHalfFrame = discardedHalfFrame,
            discardedCanonicalSourceBytes = discardedSourceBytes,
            discardedPackedCarrierBytes = discardedPackedBytes,
            markerBeforeReset = marker,
            markerAfterReset = encoder.marker,
        )
    }

    /** Explicit new carrier session/reconfigure reset. Marker phase restarts at `0x05`. */
    fun resetCarrierSession(reason: DoPCarrierSessionReset): DoPCarrierSessionResetResult {
        val marker = encoder.marker
        val discardedPartialFrameBytes = partialCanonicalFrameBytes
        val discardedHalfFrame = encoder.hasPendingHalfFrame()
        val discardedSourceBytes = discardedPartialFrameBytes + if (discardedHalfFrame) plan.channelCount else 0
        val discardedPackedBytes = pendingPackedBytes()
        totalCanonicalSourceBytesDiscardedAtReset += discardedSourceBytes.toLong()
        totalCarrierBytesDiscardedAtDiscontinuity += discardedPackedBytes.toLong()
        encoder = DoPEncoder(channelCount = plan.channelCount, initialMarker = DoPEncoder.MARKER_A)
        clearSourceAndPendingState()
        lastPackedMarker = null
        return DoPCarrierSessionResetResult(
            reason = reason,
            discardedPartialCanonicalFrameBytes = discardedPartialFrameBytes,
            discardedPendingCanonicalHalfFrame = discardedHalfFrame,
            discardedCanonicalSourceBytes = discardedSourceBytes,
            discardedPackedCarrierBytes = discardedPackedBytes,
            markerBeforeReset = marker,
            markerAfterReset = encoder.marker,
        )
    }

    /**
     * Explicit source discontinuity. This is not a pause operation and owns no external generation.
     * It discards local partial-channel input, DoP half-frame carry, and pending packed output, then
     * restarts marker phase at 0x05.
     */
    fun resetForDiscontinuity(reason: DoPDiscontinuity): DoPPipelineDiscontinuityResult {
        val discardedPartialFrameBytes = partialCanonicalFrameBytes
        val discardedHalfFrame = encoder.hasPendingHalfFrame()
        val discardedPackedBytes = pendingPackedBytes()
        totalCarrierBytesDiscardedAtDiscontinuity += discardedPackedBytes.toLong()
        encoder = DoPEncoder(channelCount = plan.channelCount, initialMarker = DoPEncoder.MARKER_A)
        clearSourceAndPendingState()
        lastPackedMarker = null
        return DoPPipelineDiscontinuityResult(
            reason = reason,
            discardedPartialCanonicalFrameBytes = discardedPartialFrameBytes,
            discardedPendingCanonicalHalfFrame = discardedHalfFrame,
            discardedPackedCarrierBytes = discardedPackedBytes,
        )
    }

    fun accounting(): DoPPipelineAccounting = DoPPipelineAccounting(
        canonicalBytesConsumed = totalCanonicalBytesConsumed,
        canonicalFramesConsumed = totalCanonicalFramesConsumed,
        runtimeFramesPacked = totalRuntimeFramesPacked,
        runtimeFramesFullyEmitted = totalRuntimeFramesFullyEmitted,
        carrierBytesEmitted = totalCarrierBytesEmitted,
        carrierBytesDiscardedAtDiscontinuity = totalCarrierBytesDiscardedAtDiscontinuity,
        contentRuntimeFramesPacked = totalContentRuntimeFramesPacked,
        idleRuntimeFramesPacked = totalIdleRuntimeFramesPacked,
        contentCarrierBytesEmitted = totalContentCarrierBytesEmitted,
        idleCarrierBytesEmitted = totalIdleCarrierBytesEmitted,
        canonicalSourceBytesDiscardedAtReset = totalCanonicalSourceBytesDiscardedAtReset,
        pendingPackedCarrierBytes = pendingPackedBytes(),
        pendingPartialCanonicalFrameBytes = partialCanonicalFrameBytes,
        hasPendingCanonicalHalfFrame = encoder.hasPendingHalfFrame(),
        lastPackedMarker = lastPackedMarker,
        nextMarker = encoder.marker,
    )

    fun hasPendingOutputOrCarry(): Boolean =
        pendingPackedBytes() > 0 || partialCanonicalFrameBytes > 0 || encoder.hasPendingHalfFrame()

    private fun packCurrentRuntimeFrame(kind: DoPPackedFrameKind) {
        check(pendingPackedBytes() == 0)
        check(pendingPackedKind == null)
        lastPackedMarker = (words[0] ushr 16) and 0xff
        val packedBytes = DoPEncoder.packWords(
            words = words,
            wordCount = plan.channelCount,
            packing = plan.packing,
            destination = packedRuntimeFrame,
        )
        check(packedBytes == plan.bytesPerRuntimeFrame) {
            "DoP packed runtime frame=$packedBytes bytes, plan=${plan.bytesPerRuntimeFrame}"
        }
        pendingPackedOffset = 0
        pendingPackedLength = packedBytes
        pendingPackedKind = kind
        totalRuntimeFramesPacked++
        when (kind) {
            DoPPackedFrameKind.CONTENT -> totalContentRuntimeFramesPacked++
            DoPPackedFrameKind.IDLE -> totalIdleRuntimeFramesPacked++
        }
    }

    private fun clearSourceAndPendingState() {
        partialCanonicalFrameBytes = 0
        clearPendingPackedState()
    }

    private fun clearPendingPackedState() {
        pendingPackedOffset = 0
        pendingPackedLength = 0
        pendingPackedKind = null
    }

    private data class EmitResult(
        val bytes: Int,
        val framesCompleted: Int,
    )

    private fun emitPending(
        destination: ByteArray,
        destinationOffset: Int,
        destinationByteCount: Int,
    ): EmitResult {
        if (destinationByteCount == 0 || pendingPackedBytes() == 0) return EmitResult(0, 0)
        val count = minOf(destinationByteCount, pendingPackedBytes())
        packedRuntimeFrame.copyInto(
            destination = destination,
            destinationOffset = destinationOffset,
            startIndex = pendingPackedOffset,
            endIndex = pendingPackedOffset + count,
        )
        pendingPackedOffset += count
        totalCarrierBytesEmitted += count.toLong()
        when (checkNotNull(pendingPackedKind)) {
            DoPPackedFrameKind.CONTENT -> totalContentCarrierBytesEmitted += count.toLong()
            DoPPackedFrameKind.IDLE -> totalIdleCarrierBytesEmitted += count.toLong()
        }

        var completed = 0
        if (pendingPackedOffset == pendingPackedLength) {
            pendingPackedOffset = 0
            pendingPackedLength = 0
            pendingPackedKind = null
            totalRuntimeFramesFullyEmitted++
            completed = 1
        }
        return EmitResult(count, completed)
    }

    private fun pendingPackedBytes(): Int = pendingPackedLength - pendingPackedOffset

    companion object {
        private fun validatePlan(plan: DoPCarrierPlan) {
            require(plan.dsdBitRateHz > 0L)
            require(plan.channelCount > 0)
            require(plan.runtimeFrameRateHz > 0L)
            require(plan.bytesPerRuntimeFrame > 0)
            require(plan.maxRuntimeFramesPerServiceInterval > 0L)
            require(plan.requiredMaxBytesPerServiceInterval > 0L)
            require(plan.dsdBitRateHz % 16L == 0L) {
                "DoP plan DSD bit rate must be exactly divisible by 16"
            }
            require(plan.runtimeFrameRateHz == plan.dsdBitRateHz / 16L) {
                "DoP plan runtime frame rate does not match DSD/16"
            }
            val expectedBytesPerFrame = plan.packing.bytesPerChannel.toLong() * plan.channelCount.toLong()
            require(expectedBytesPerFrame <= Int.MAX_VALUE.toLong()) {
                "DoP plan runtime-frame geometry overflows"
            }
            require(plan.bytesPerRuntimeFrame == expectedBytesPerFrame.toInt()) {
                "DoP plan bytes/runtime-frame does not match packing/channel geometry"
            }
            require(plan.maxRuntimeFramesPerServiceInterval <= Long.MAX_VALUE / plan.bytesPerRuntimeFrame.toLong()) {
                "DoP plan capacity geometry overflows"
            }
            require(
                plan.requiredMaxBytesPerServiceInterval ==
                    plan.maxRuntimeFramesPerServiceInterval * plan.bytesPerRuntimeFrame.toLong(),
            ) {
                "DoP plan required capacity does not match runtime-frame geometry"
            }
        }
    }
}
