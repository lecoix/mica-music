package com.afalphy.sylvakru

object UsbExclusiveNative {
    init {
        System.loadLibrary("sylvakru_usb_exclusive")
    }

    data class OpenResult(val sessionId: Long? = null, val error: String? = null)

    fun open(
        epoch: Long,
        fd: Int,
        interfaceNumber: Int,
        alternateSetting: Int,
        endpointAddress: Int,
        maxPacketSize: Int,
        feedbackEndpointAddress: Int,
        feedbackMaxPacketSize: Int,
        interfaceAlreadyClaimed: Boolean,
        deferTargetAltUntilConfigured: Boolean,
        resetAltBeforeConfigured: Boolean,
    ): OpenResult {
        val sessionId = openRaw(
            epoch,
            fd,
            interfaceNumber,
            alternateSetting,
            endpointAddress,
            maxPacketSize,
            feedbackEndpointAddress,
            feedbackMaxPacketSize,
            interfaceAlreadyClaimed,
            deferTargetAltUntilConfigured,
            resetAltBeforeConfigured,
        )
        return if (sessionId > 0L) OpenResult(sessionId = sessionId) else OpenResult(error = lastError())
    }

    external fun publishActiveEpoch(epoch: Long)

    external fun isCurrent(epoch: Long, sessionId: Long): Boolean

    private external fun openRaw(
        epoch: Long,
        fd: Int,
        interfaceNumber: Int,
        alternateSetting: Int,
        endpointAddress: Int,
        maxPacketSize: Int,
        feedbackEndpointAddress: Int,
        feedbackMaxPacketSize: Int,
        interfaceAlreadyClaimed: Boolean,
        deferTargetAltUntilConfigured: Boolean,
        resetAltBeforeConfigured: Boolean,
    ): Long

    private external fun lastError(): String?

    external fun activateConfiguredAlt(epoch: Long, sessionId: Long, alternateSetting: Int): String?

    external fun configureOutputStream(
        epoch: Long,
        sessionId: Long,
        sampleRate: Int,
        packetsPerSecond: Int,
        bytesPerFrame: Int,
        targetBufferMs: Int,
    ): String?

    external fun writeFrames(epoch: Long, sessionId: Long, bytes: ByteArray, length: Int): String?

    external fun beginSourceTimeline(epoch: Long, sessionId: Long): Long

    external fun writeSourceFrames(
        epoch: Long,
        sessionId: Long,
        sourceTimelineGeneration: Long,
        bytes: ByteArray,
        length: Int,
    ): String?

    external fun consumedSourceFrames(
        epoch: Long,
        sessionId: Long,
        sourceTimelineGeneration: Long,
    ): Long

    /** -1 means in-flight transfers are still draining; -2 means terminal/stale error. */
    external fun reserveOutputTailPaddingFrames(epoch: Long, sessionId: Long): Int

    external fun commitOutputTailPadding(epoch: Long, sessionId: Long): String?

    external fun writePcm(epoch: Long, sessionId: Long, bytes: ByteArray, length: Int): String?

    external fun writeIsoPackets(
        epoch: Long,
        sessionId: Long,
        bytes: ByteArray,
        packetLengths: IntArray,
        packetCount: Int,
    ): String?

    external fun setIsoPacketSize(epoch: Long, sessionId: Long, packetSize: Int): String?

    external fun feedbackFramesPerPacketQ16(epoch: Long, sessionId: Long): Int

    external fun transportTelemetry(epoch: Long, sessionId: Long): LongArray

    external fun flushOutput(epoch: Long, sessionId: Long): String?

    external fun close(epoch: Long, sessionId: Long): String?
}
