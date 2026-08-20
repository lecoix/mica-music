package com.afalphy.sylvakru

object UsbExclusiveNative {
    init {
        System.loadLibrary("sylvakru_usb_exclusive")
    }

    external fun open(
        fd: Int,
        interfaceNumber: Int,
        alternateSetting: Int,
        endpointAddress: Int,
        maxPacketSize: Int,
        feedbackEndpointAddress: Int,
        feedbackMaxPacketSize: Int,
        interfaceAlreadyClaimed: Boolean,
    ): String?

    external fun writePcm(bytes: ByteArray, length: Int): String?

    external fun writeIsoPackets(bytes: ByteArray, packetLengths: IntArray, packetCount: Int): String?

    external fun setIsoPacketSize(packetSize: Int)

    external fun feedbackFramesPerPacketQ16(): Int

    external fun transportTelemetry(): LongArray

    external fun setMaxPendingOutputUrbs(maxPendingUrbs: Int)

    external fun flushOutput(): String?

    external fun close()
}
