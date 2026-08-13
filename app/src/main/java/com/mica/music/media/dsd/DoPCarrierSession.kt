package com.mica.music.media.dsd

/**
 * Pure carrier-content session facade for future exact-carrier integration.
 *
 * This object is deliberately not a USB/session owner. It owns only DoP carrier-content continuity:
 * one marker phase shared by real content, valid DSD idle frames, source resets and explicit carrier
 * resets. P3 remains responsible for external device/transport/session lifetime.
 */
class DoPCarrierSession(
    val plan: DoPCarrierPlan,
) {
    private val pipeline = DoPRuntimePipeline(plan)

    fun writeContentBytes(
        source: ByteArray,
        sourceOffset: Int = 0,
        sourceByteCount: Int = source.size - sourceOffset,
        destination: ByteArray,
        destinationOffset: Int = 0,
        destinationByteCount: Int = destination.size - destinationOffset,
    ): DoPPipelineByteWriteResult = pipeline.writeBytes(
        source = source,
        sourceOffset = sourceOffset,
        sourceByteCount = sourceByteCount,
        destination = destination,
        destinationOffset = destinationOffset,
        destinationByteCount = destinationByteCount,
    )

    fun writeIdleFrames(
        frameCount: Int,
        destination: ByteArray,
        destinationOffset: Int = 0,
        destinationByteCount: Int = destination.size - destinationOffset,
    ): DoPPipelineIdleWriteResult = pipeline.writeIdleFrames(
        frameCount = frameCount,
        destination = destination,
        destinationOffset = destinationOffset,
        destinationByteCount = destinationByteCount,
    )

    fun finishSource(
        destination: ByteArray,
        destinationOffset: Int = 0,
        destinationByteCount: Int = destination.size - destinationOffset,
    ): DoPPipelineDrainResult = pipeline.drainEndOfSource(
        destination = destination,
        destinationOffset = destinationOffset,
        destinationByteCount = destinationByteCount,
    )

    /** Seek/next-source while retaining the same exact carrier session and marker phase. */
    fun resetSource(reason: DoPDiscontinuity): DoPSourceResetResult =
        pipeline.resetSourceForRetainedCarrier(reason)

    /** New carrier session or reconfigure: stale source state is discarded and marker restarts 0x05. */
    fun resetCarrier(reason: DoPCarrierSessionReset): DoPCarrierSessionResetResult =
        pipeline.resetCarrierSession(reason)

    fun accounting(): DoPPipelineAccounting = pipeline.accounting()

    fun hasPendingOutputOrCarry(): Boolean = pipeline.hasPendingOutputOrCarry()
}
