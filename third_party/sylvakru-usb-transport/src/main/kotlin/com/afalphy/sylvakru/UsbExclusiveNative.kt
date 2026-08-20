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
    ): Long

    private external fun lastError(): String?

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

    external fun setMaxPendingOutputUrbs(epoch: Long, sessionId: Long, maxPendingUrbs: Int): String?

    external fun flushOutput(epoch: Long, sessionId: Long): String?

    external fun close(epoch: Long, sessionId: Long): String?
}
