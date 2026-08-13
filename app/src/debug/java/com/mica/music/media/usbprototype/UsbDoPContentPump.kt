package com.mica.music.media.usbprototype

import com.mica.music.media.dsd.DsdContainerReader
import com.mica.music.media.usb.ExactCarrierFeedStatus
import com.mica.music.media.usb.ExactCarrierFeeder

internal data class UsbDoPContentPumpSnapshot(
    val readerFramesRead: Long,
    val readerCanonicalBytesRead: Long,
    val canonicalBytesConsumed: Long,
    val pendingCanonicalBytes: Int,
    val readerEof: Boolean,
    val feederStagedBytes: Int,
    val feederUpstreamPendingBytes: Int,
    val feederContractError: String?,
)

internal data class UsbDoPContentPumpStep(
    val readerFramesAdded: Int,
    val canonicalBytesConsumed: Int,
    val sinkBytesAccepted: Int,
    val status: ExactCarrierFeedStatus,
    val eof: Boolean,
)

/** Debug-only source pump retaining each reader byte until P5 acknowledges canonical consumption. */
internal class UsbDoPContentPump(
    private val reader: DsdContainerReader,
    private val feeder: ExactCarrierFeeder,
    chunkFrames: Int = DEFAULT_CHUNK_FRAMES,
) {
    private val channels = reader.info.channelCount
    private val chunkFrames = chunkFrames.also { require(it > 0) }
    private val readBuffer = ByteArray(this.chunkFrames * channels)
    private var pendingOffset = 0
    private var pendingBytes = 0
    private var eof = false
    private var readerFramesRead = 0L
    private var readerCanonicalBytesRead = 0L
    private var canonicalBytesConsumed = 0L

    fun step(allowReaderRead: Boolean = true): UsbDoPContentPumpStep {
        val feederSnapshot = feeder.snapshot()
        feederSnapshot.contractError?.let {
            return UsbDoPContentPumpStep(0, 0, 0, ExactCarrierFeedStatus.FAILED, eof)
        }

        if (pendingBytes == 0 &&
            (feederSnapshot.stagedCarrierBytes.isNotEmpty() ||
                feederSnapshot.upstreamPendingPackedCarrierBytes > 0)
        ) {
            val pump = feeder.pump()
            return UsbDoPContentPumpStep(0, 0, pump.sinkBytesAccepted, pump.status, eof)
        }

        var framesAdded = 0
        if (pendingBytes == 0 && allowReaderRead && !eof) {
            val frames = reader.readFrames(readBuffer, 0, chunkFrames)
            require(frames in 0..chunkFrames)
            if (frames == 0) {
                eof = true
            } else {
                framesAdded = frames
                pendingOffset = 0
                pendingBytes = frames * channels
                readerFramesRead += frames.toLong()
                readerCanonicalBytesRead += pendingBytes.toLong()
            }
        }

        if (pendingBytes == 0) {
            return UsbDoPContentPumpStep(framesAdded, 0, 0, ExactCarrierFeedStatus.NO_PROGRESS, eof)
        }

        val feed = feeder.writeContentBytes(readBuffer, pendingOffset, pendingBytes)
        if (feed.canonicalBytesConsumed > 0) {
            pendingOffset += feed.canonicalBytesConsumed
            pendingBytes -= feed.canonicalBytesConsumed
            canonicalBytesConsumed += feed.canonicalBytesConsumed.toLong()
            if (pendingBytes == 0) pendingOffset = 0
        }
        return UsbDoPContentPumpStep(
            readerFramesAdded = framesAdded,
            canonicalBytesConsumed = feed.canonicalBytesConsumed,
            sinkBytesAccepted = feed.sinkBytesAccepted,
            status = feed.status,
            eof = eof,
        )
    }

    fun drainBuffered(maxSteps: Int = 100_000): Boolean {
        repeat(maxSteps) {
            if (isCleanBoundary()) return true
            val step = step(allowReaderRead = false)
            if (step.status == ExactCarrierFeedStatus.FAILED) return false
            if (step.status == ExactCarrierFeedStatus.NO_PROGRESS && !isCleanBoundary()) return false
        }
        return false
    }

    fun isCleanBoundary(): Boolean {
        val snapshot = feeder.snapshot()
        val accounting = feeder.accounting()
        return pendingBytes == 0 &&
            snapshot.stagedCarrierBytes.isEmpty() &&
            snapshot.upstreamPendingPackedCarrierBytes == 0 &&
            snapshot.contractError == null &&
            accounting.pendingPackedCarrierBytes == 0 &&
            accounting.pendingPartialCanonicalFrameBytes == 0 &&
            !accounting.hasPendingCanonicalHalfFrame
    }

    fun snapshot(): UsbDoPContentPumpSnapshot {
        val feederSnapshot = feeder.snapshot()
        return UsbDoPContentPumpSnapshot(
            readerFramesRead = readerFramesRead,
            readerCanonicalBytesRead = readerCanonicalBytesRead,
            canonicalBytesConsumed = canonicalBytesConsumed,
            pendingCanonicalBytes = pendingBytes,
            readerEof = eof,
            feederStagedBytes = feederSnapshot.stagedCarrierBytes.size,
            feederUpstreamPendingBytes = feederSnapshot.upstreamPendingPackedCarrierBytes,
            feederContractError = feederSnapshot.contractError?.toString(),
        )
    }

    companion object {
        const val DEFAULT_CHUNK_FRAMES = 4_096
    }
}