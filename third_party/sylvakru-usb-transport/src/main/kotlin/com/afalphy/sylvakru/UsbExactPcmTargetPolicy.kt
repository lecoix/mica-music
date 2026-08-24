package com.afalphy.sylvakru

object UsbExactPcmTargetPolicy {
    /** Accept integer PCM only when the USB slot preserves every source bit; widening is lossless. */
    fun accepts(inputBitDepth: Int, usbBytesPerSample: Int, usbBitResolution: Int): Boolean {
        if (inputBitDepth !in setOf(16, 24, 32)) return false
        if (usbBytesPerSample !in 2..4) return false
        if (usbBitResolution !in inputBitDepth..(usbBytesPerSample * 8)) return false
        return usbBytesPerSample * 8 >= inputBitDepth
    }
}
