package com.mica.music.media.usb

import com.mica.music.media.dsd.DoPCarrierGapWriteResult
import com.mica.music.media.dsd.DoPCarrierSession
import com.mica.music.media.dsd.DoPCarrierSessionReset
import com.mica.music.media.dsd.DoPCarrierSessionResetResult
import com.mica.music.media.dsd.DoPDiscontinuity
import com.mica.music.media.dsd.DoPGapBlockedReason
import com.mica.music.media.dsd.DoPPipelineAccounting
import com.mica.music.media.dsd.DoPSourceResetResult

/** Frame-aligned transport seam for carrier bytes already packed by P5. */
internal interface ExactCarrierFrameSink {
    val bytesPerRuntimeFrame: Int

    /** Returns the number of bytes accepted from [source]. Positive results must be whole frames. */
    fun writeCarrierFrames(source: ByteArray, offset: Int, byteCount: Int): Int
}

internal enum class ExactCarrierFeederContractErrorCode {
    SINK_FRAME_SIZE_MISMATCH,
    SINK_NEGATIVE_ACCEPTANCE,
    SINK_OVER_ACCEPTANCE,
    SINK_NON_FRAME_ALIGNED_ACCEPTANCE,
    STAGING_OVERFLOW,
    UPSTREAM_INVALID_EMISSION,
    UPSTREAM_PENDING_WITHOUT_STAGED_PREFIX,
    UPSTREAM_PARTIAL_STAGING_WITHOUT_PENDING_OUTPUT,
    UPSTREAM_FLUSH_NO_PROGRESS,
}

internal data class ExactCarrierFeederContractError(
    val code: ExactCarrierFeederContractErrorCode,
    val detail: String,
)

internal enum class ExactCarrierFeedStatus {
    PROGRESSED,
    DRAINING_STAGED,
    BACKPRESSURED,
    NO_PROGRESS,
    BLOCKED,
    FAILED,
}

internal data class ExactCarrierPumpResult(
    val status: ExactCarrierFeedStatus,
    val carrierBytesFlushedFromSession: Int,
    val sinkBytesAccepted: Int,
    val stagedBytesRemaining: Int,
    val error: ExactCarrierFeederContractError? = null,
)

internal data class ExactCarrierContentFeedResult(
    val status: ExactCarrierFeedStatus,
    val canonicalBytesConsumed: Int,
    val carrierBytesCapturedFromSession: Int,
    val sinkBytesAccepted: Int,
    val stagedBytesRemaining: Int,
    val error: ExactCarrierFeederContractError? = null,
)

internal data class ExactCarrierGapFeedResult(
    val status: ExactCarrierFeedStatus,
    val requestedGapFrames: Int,
    val gapFramesAccepted: Int,
    val blockedReason: DoPGapBlockedReason?,
    val carrierBytesCapturedFromSession: Int,
    val sinkBytesAccepted: Int,
    val stagedBytesRemaining: Int,
    val error: ExactCarrierFeederContractError? = null,
)

internal enum class ExactCarrierResetBlockedReason {
    STAGED_CARRIER_BYTES,
    UPSTREAM_PENDING_CARRIER_OUTPUT,
    CONTRACT_FAILED,
}

internal data class ExactCarrierSourceResetFeedResult(
    val applied: Boolean,
    val blockedReason: ExactCarrierResetBlockedReason?,
    val reset: DoPSourceResetResult?,
    val stagedBytesRemaining: Int,
    val error: ExactCarrierFeederContractError? = null,
)

internal data class ExactCarrierSessionResetFeedResult(
    val applied: Boolean,
    val blockedReason: ExactCarrierResetBlockedReason?,
    val reset: DoPCarrierSessionResetResult?,
    val stagedBytesRemaining: Int,
    val error: ExactCarrierFeederContractError? = null,
)

internal data class ExactCarrierFeederSnapshot(
    val stagedCarrierBytes: ByteArray,
    val upstreamPendingPackedCarrierBytes: Int,
    val contractError: ExactCarrierFeederContractError?,
)

/**
 * Pure P3 backpressure adapter between P5's chronology-owning [DoPCarrierSession] and a
 * frame-aligned carrier sink.
 *
 * Carrier bytes emitted by P5 become committed old-stream output immediately. They are retained in
 * this bounded staging buffer until the sink accepts them. No content/gap/reset call may overtake
 * staged bytes. If P5 itself has a partially emitted packed frame, only [flushCarrierOutput] is used
 * to finish that already-accepted frame; a zero-frame gap is never used as a flush surrogate.
 */
internal class ExactCarrierFeeder(
    private val session: DoPCarrierSession,
    private val sink: ExactCarrierFrameSink,
    stagingFrameCapacity: Int = DEFAULT_STAGING_FRAME_CAPACITY,
    private val upstreamEmissionChunkBytes: Int =
        session.plan.bytesPerRuntimeFrame * stagingFrameCapacity,
) {
    private val frameBytes = session.plan.bytesPerRuntimeFrame
    private val stagingCapacityBytes = frameBytes * stagingFrameCapacity
    private val staging = ByteArray(stagingCapacityBytes)
    private var stagedBytes = 0
    private var contractError: ExactCarrierFeederContractError? = null

    init {
        require(stagingFrameCapacity > 0)
        require(frameBytes > 0)
        require(upstreamEmissionChunkBytes in 1..stagingCapacityBytes)
        if (sink.bytesPerRuntimeFrame != frameBytes) {
            failContract(
                ExactCarrierFeederContractErrorCode.SINK_FRAME_SIZE_MISMATCH,
                "sink frame bytes=${sink.bytesPerRuntimeFrame} plan frame bytes=$frameBytes",
            )
        }
    }

    fun writeContentBytes(
        source: ByteArray,
        sourceOffset: Int = 0,
        sourceByteCount: Int = source.size - sourceOffset,
    ): ExactCarrierContentFeedResult {
        require(sourceOffset >= 0 && sourceByteCount >= 0 && sourceOffset <= source.size)
        require(sourceByteCount <= source.size - sourceOffset)
        contractError?.let { return failedContent(it) }

        if (stagedBytes > 0 || upstreamPendingPackedBytes() > 0) {
            val pump = pump()
            return ExactCarrierContentFeedResult(
                status = if (pump.status == ExactCarrierFeedStatus.PROGRESSED) {
                    ExactCarrierFeedStatus.DRAINING_STAGED
                } else {
                    pump.status
                },
                canonicalBytesConsumed = 0,
                carrierBytesCapturedFromSession = pump.carrierBytesFlushedFromSession,
                sinkBytesAccepted = pump.sinkBytesAccepted,
                stagedBytesRemaining = pump.stagedBytesRemaining,
                error = pump.error,
            )
        }

        if (sourceByteCount == 0) {
            return ExactCarrierContentFeedResult(
                status = ExactCarrierFeedStatus.NO_PROGRESS,
                canonicalBytesConsumed = 0,
                carrierBytesCapturedFromSession = 0,
                sinkBytesAccepted = 0,
                stagedBytesRemaining = 0,
            )
        }

        val upstreamBuffer = ByteArray(upstreamEmissionChunkBytes)
        val written = session.writeContentBytes(
            source = source,
            sourceOffset = sourceOffset,
            sourceByteCount = sourceByteCount,
            destination = upstreamBuffer,
        )
        if (!captureUpstreamBytes(upstreamBuffer, written.carrierBytesEmitted)) {
            return failedContent(requireNotNull(contractError))
        }

        val pump = if (stagedBytes > 0) pump() else noProgressPump()
        val captured = written.carrierBytesEmitted + pump.carrierBytesFlushedFromSession
        val status = combineGenerationStatus(
            upstreamProgress = written.canonicalBytesConsumed > 0 || captured > 0,
            pump = pump,
        )
        return ExactCarrierContentFeedResult(
            status = status,
            canonicalBytesConsumed = written.canonicalBytesConsumed,
            carrierBytesCapturedFromSession = captured,
            sinkBytesAccepted = pump.sinkBytesAccepted,
            stagedBytesRemaining = stagedBytes,
            error = pump.error ?: contractError,
        )
    }

    fun writeGapFrames(frameCount: Int): ExactCarrierGapFeedResult {
        require(frameCount >= 0)
        contractError?.let { return failedGap(frameCount, it) }

        // Corrected P5 contract: zero-frame gap is a strict no-op, including while output is pending.
        if (frameCount == 0) {
            return ExactCarrierGapFeedResult(
                status = ExactCarrierFeedStatus.NO_PROGRESS,
                requestedGapFrames = 0,
                gapFramesAccepted = 0,
                blockedReason = null,
                carrierBytesCapturedFromSession = 0,
                sinkBytesAccepted = 0,
                stagedBytesRemaining = stagedBytes,
            )
        }

        if (stagedBytes > 0 || upstreamPendingPackedBytes() > 0) {
            val pump = pump()
            return ExactCarrierGapFeedResult(
                status = if (pump.status == ExactCarrierFeedStatus.PROGRESSED) {
                    ExactCarrierFeedStatus.DRAINING_STAGED
                } else {
                    pump.status
                },
                requestedGapFrames = frameCount,
                gapFramesAccepted = 0,
                blockedReason = null,
                carrierBytesCapturedFromSession = pump.carrierBytesFlushedFromSession,
                sinkBytesAccepted = pump.sinkBytesAccepted,
                stagedBytesRemaining = pump.stagedBytesRemaining,
                error = pump.error,
            )
        }

        val upstreamBuffer = ByteArray(upstreamEmissionChunkBytes)
        val written: DoPCarrierGapWriteResult = session.writeGapFrames(
            frameCount = frameCount,
            destination = upstreamBuffer,
        )
        if (!captureUpstreamBytes(upstreamBuffer, written.carrierBytesEmitted)) {
            return failedGap(frameCount, requireNotNull(contractError))
        }

        val pump = if (stagedBytes > 0) pump() else noProgressPump()
        val captured = written.carrierBytesEmitted + pump.carrierBytesFlushedFromSession
        val upstreamProgress = written.gapFramesAccepted > 0 || captured > 0
        val status = when {
            pump.error != null -> ExactCarrierFeedStatus.FAILED
            written.blockedReason != null -> ExactCarrierFeedStatus.BLOCKED
            pump.status == ExactCarrierFeedStatus.BACKPRESSURED -> ExactCarrierFeedStatus.BACKPRESSURED
            upstreamProgress || pump.sinkBytesAccepted > 0 -> ExactCarrierFeedStatus.PROGRESSED
            else -> ExactCarrierFeedStatus.NO_PROGRESS
        }
        return ExactCarrierGapFeedResult(
            status = status,
            requestedGapFrames = frameCount,
            gapFramesAccepted = written.gapFramesAccepted,
            blockedReason = written.blockedReason,
            carrierBytesCapturedFromSession = captured,
            sinkBytesAccepted = pump.sinkBytesAccepted,
            stagedBytesRemaining = stagedBytes,
            error = pump.error ?: contractError,
        )
    }

    /** Drives only already-committed carrier output; never consumes canonical source or gap budget. */
    fun pump(): ExactCarrierPumpResult {
        contractError?.let { return failedPump(it) }

        if (stagedBytes == 0) {
            val pending = upstreamPendingPackedBytes()
            if (pending == 0) return noProgressPump()
            return failedPump(
                failContract(
                    ExactCarrierFeederContractErrorCode.UPSTREAM_PENDING_WITHOUT_STAGED_PREFIX,
                    "P5 holds $pending packed bytes but feeder has no emitted prefix",
                ),
            )
        }

        val alignedOffer = stagedBytes - stagedBytes % frameBytes
        if (alignedOffer > 0) {
            return offerAlignedPrefixToSink(alignedOffer, flushedFromSession = 0)
        }

        // Only a partial already-emitted frame remains. It cannot go to the sink; complete exactly
        // that P5-packed frame via the explicit flush API, which accepts no new content/gap frame.
        val pendingBefore = upstreamPendingPackedBytes()
        if (pendingBefore <= 0) {
            return failedPump(
                failContract(
                    ExactCarrierFeederContractErrorCode.UPSTREAM_PARTIAL_STAGING_WITHOUT_PENDING_OUTPUT,
                    "feeder retains $stagedBytes partial bytes but P5 has no packed tail",
                ),
            )
        }
        val room = stagingCapacityBytes - stagedBytes
        if (room <= 0) {
            return failedPump(
                failContract(
                    ExactCarrierFeederContractErrorCode.STAGING_OVERFLOW,
                    "no room to complete P5 packed tail; staged=$stagedBytes capacity=$stagingCapacityBytes",
                ),
            )
        }

        val flushBuffer = ByteArray(room)
        val flush = session.flushCarrierOutput(destination = flushBuffer)
        if (flush.carrierBytesEmitted <= 0 || flush.carrierBytesEmitted > room) {
            return failedPump(
                failContract(
                    ExactCarrierFeederContractErrorCode.UPSTREAM_FLUSH_NO_PROGRESS,
                    "pendingBefore=$pendingBefore flushEmitted=${flush.carrierBytesEmitted} room=$room",
                ),
            )
        }
        if (!captureUpstreamBytes(flushBuffer, flush.carrierBytesEmitted)) {
            return failedPump(requireNotNull(contractError))
        }

        val pendingAfter = upstreamPendingPackedBytes()
        if (stagedBytes % frameBytes != 0 && pendingAfter == 0) {
            return failedPump(
                failContract(
                    ExactCarrierFeederContractErrorCode.UPSTREAM_PARTIAL_STAGING_WITHOUT_PENDING_OUTPUT,
                    "flush ended with unaligned staged=$stagedBytes and no P5 packed tail",
                ),
            )
        }

        val nowAligned = stagedBytes - stagedBytes % frameBytes
        return if (nowAligned > 0) {
            offerAlignedPrefixToSink(
                alignedOffer = nowAligned,
                flushedFromSession = flush.carrierBytesEmitted,
            )
        } else {
            ExactCarrierPumpResult(
                status = ExactCarrierFeedStatus.PROGRESSED,
                carrierBytesFlushedFromSession = flush.carrierBytesEmitted,
                sinkBytesAccepted = 0,
                stagedBytesRemaining = stagedBytes,
            )
        }
    }

    fun resetSource(reason: DoPDiscontinuity): ExactCarrierSourceResetFeedResult {
        contractError?.let {
            return ExactCarrierSourceResetFeedResult(
                applied = false,
                blockedReason = ExactCarrierResetBlockedReason.CONTRACT_FAILED,
                reset = null,
                stagedBytesRemaining = stagedBytes,
                error = it,
            )
        }
        if (stagedBytes > 0) {
            return ExactCarrierSourceResetFeedResult(
                applied = false,
                blockedReason = ExactCarrierResetBlockedReason.STAGED_CARRIER_BYTES,
                reset = null,
                stagedBytesRemaining = stagedBytes,
            )
        }
        if (upstreamPendingPackedBytes() > 0) {
            return ExactCarrierSourceResetFeedResult(
                applied = false,
                blockedReason = ExactCarrierResetBlockedReason.UPSTREAM_PENDING_CARRIER_OUTPUT,
                reset = null,
                stagedBytesRemaining = 0,
            )
        }
        return ExactCarrierSourceResetFeedResult(
            applied = true,
            blockedReason = null,
            reset = session.resetSource(reason),
            stagedBytesRemaining = 0,
        )
    }

    fun resetCarrier(reason: DoPCarrierSessionReset): ExactCarrierSessionResetFeedResult {
        contractError?.let {
            return ExactCarrierSessionResetFeedResult(
                applied = false,
                blockedReason = ExactCarrierResetBlockedReason.CONTRACT_FAILED,
                reset = null,
                stagedBytesRemaining = stagedBytes,
                error = it,
            )
        }
        if (stagedBytes > 0) {
            return ExactCarrierSessionResetFeedResult(
                applied = false,
                blockedReason = ExactCarrierResetBlockedReason.STAGED_CARRIER_BYTES,
                reset = null,
                stagedBytesRemaining = stagedBytes,
            )
        }
        if (upstreamPendingPackedBytes() > 0) {
            return ExactCarrierSessionResetFeedResult(
                applied = false,
                blockedReason = ExactCarrierResetBlockedReason.UPSTREAM_PENDING_CARRIER_OUTPUT,
                reset = null,
                stagedBytesRemaining = 0,
            )
        }
        return ExactCarrierSessionResetFeedResult(
            applied = true,
            blockedReason = null,
            reset = session.resetCarrier(reason),
            stagedBytesRemaining = 0,
        )
    }

    fun accounting(): DoPPipelineAccounting = session.accounting()

    fun snapshot(): ExactCarrierFeederSnapshot = ExactCarrierFeederSnapshot(
        stagedCarrierBytes = staging.copyOf(stagedBytes),
        upstreamPendingPackedCarrierBytes = upstreamPendingPackedBytes(),
        contractError = contractError,
    )

    private fun captureUpstreamBytes(source: ByteArray, byteCount: Int): Boolean {
        if (byteCount < 0 || byteCount > source.size) {
            failContract(
                ExactCarrierFeederContractErrorCode.UPSTREAM_INVALID_EMISSION,
                "P5 emitted byteCount=$byteCount from buffer size=${source.size}",
            )
            return false
        }
        if (byteCount == 0) return true
        if (byteCount > stagingCapacityBytes - stagedBytes) {
            failContract(
                ExactCarrierFeederContractErrorCode.STAGING_OVERFLOW,
                "capture=$byteCount staged=$stagedBytes capacity=$stagingCapacityBytes",
            )
            return false
        }
        source.copyInto(
            destination = staging,
            destinationOffset = stagedBytes,
            startIndex = 0,
            endIndex = byteCount,
        )
        stagedBytes += byteCount
        return true
    }

    private fun offerAlignedPrefixToSink(
        alignedOffer: Int,
        flushedFromSession: Int,
    ): ExactCarrierPumpResult {
        check(alignedOffer > 0 && alignedOffer <= stagedBytes && alignedOffer % frameBytes == 0)
        val offeredCopy = staging.copyOf(alignedOffer)
        val accepted = sink.writeCarrierFrames(offeredCopy, 0, alignedOffer)
        val error = when {
            accepted < 0 -> failContract(
                ExactCarrierFeederContractErrorCode.SINK_NEGATIVE_ACCEPTANCE,
                "sink accepted=$accepted offered=$alignedOffer",
            )
            accepted > alignedOffer -> failContract(
                ExactCarrierFeederContractErrorCode.SINK_OVER_ACCEPTANCE,
                "sink accepted=$accepted offered=$alignedOffer",
            )
            accepted > 0 && accepted % frameBytes != 0 -> failContract(
                ExactCarrierFeederContractErrorCode.SINK_NON_FRAME_ALIGNED_ACCEPTANCE,
                "sink accepted=$accepted frameBytes=$frameBytes offered=$alignedOffer",
            )
            else -> null
        }
        if (error != null) {
            return ExactCarrierPumpResult(
                status = ExactCarrierFeedStatus.FAILED,
                carrierBytesFlushedFromSession = flushedFromSession,
                sinkBytesAccepted = 0,
                stagedBytesRemaining = stagedBytes,
                error = error,
            )
        }
        if (accepted == 0) {
            return ExactCarrierPumpResult(
                status = ExactCarrierFeedStatus.BACKPRESSURED,
                carrierBytesFlushedFromSession = flushedFromSession,
                sinkBytesAccepted = 0,
                stagedBytesRemaining = stagedBytes,
            )
        }

        removeStagedPrefix(accepted)
        return ExactCarrierPumpResult(
            status = ExactCarrierFeedStatus.PROGRESSED,
            carrierBytesFlushedFromSession = flushedFromSession,
            sinkBytesAccepted = accepted,
            stagedBytesRemaining = stagedBytes,
        )
    }

    private fun removeStagedPrefix(byteCount: Int) {
        check(byteCount in 1..stagedBytes)
        val remaining = stagedBytes - byteCount
        if (remaining > 0) {
            staging.copyInto(
                destination = staging,
                destinationOffset = 0,
                startIndex = byteCount,
                endIndex = stagedBytes,
            )
        }
        stagedBytes = remaining
    }

    private fun upstreamPendingPackedBytes(): Int = session.accounting().pendingPackedCarrierBytes

    private fun combineGenerationStatus(
        upstreamProgress: Boolean,
        pump: ExactCarrierPumpResult,
    ): ExactCarrierFeedStatus = when {
        pump.error != null -> ExactCarrierFeedStatus.FAILED
        pump.status == ExactCarrierFeedStatus.BACKPRESSURED -> ExactCarrierFeedStatus.BACKPRESSURED
        upstreamProgress || pump.sinkBytesAccepted > 0 -> ExactCarrierFeedStatus.PROGRESSED
        else -> ExactCarrierFeedStatus.NO_PROGRESS
    }

    private fun failContract(
        code: ExactCarrierFeederContractErrorCode,
        detail: String,
    ): ExactCarrierFeederContractError {
        val existing = contractError
        if (existing != null) return existing
        return ExactCarrierFeederContractError(code, detail).also { contractError = it }
    }

    private fun noProgressPump() = ExactCarrierPumpResult(
        status = ExactCarrierFeedStatus.NO_PROGRESS,
        carrierBytesFlushedFromSession = 0,
        sinkBytesAccepted = 0,
        stagedBytesRemaining = stagedBytes,
    )

    private fun failedPump(error: ExactCarrierFeederContractError) = ExactCarrierPumpResult(
        status = ExactCarrierFeedStatus.FAILED,
        carrierBytesFlushedFromSession = 0,
        sinkBytesAccepted = 0,
        stagedBytesRemaining = stagedBytes,
        error = error,
    )

    private fun failedContent(error: ExactCarrierFeederContractError) = ExactCarrierContentFeedResult(
        status = ExactCarrierFeedStatus.FAILED,
        canonicalBytesConsumed = 0,
        carrierBytesCapturedFromSession = 0,
        sinkBytesAccepted = 0,
        stagedBytesRemaining = stagedBytes,
        error = error,
    )

    private fun failedGap(
        requestedFrames: Int,
        error: ExactCarrierFeederContractError,
    ) = ExactCarrierGapFeedResult(
        status = ExactCarrierFeedStatus.FAILED,
        requestedGapFrames = requestedFrames,
        gapFramesAccepted = 0,
        blockedReason = null,
        carrierBytesCapturedFromSession = 0,
        sinkBytesAccepted = 0,
        stagedBytesRemaining = stagedBytes,
        error = error,
    )

    private companion object {
        const val DEFAULT_STAGING_FRAME_CAPACITY = 8
    }
}
