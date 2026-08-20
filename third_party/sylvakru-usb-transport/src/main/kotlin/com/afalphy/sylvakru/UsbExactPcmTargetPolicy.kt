package com.afalphy.sylvakru

object UsbExactPcmTargetPolicy {
    fun accepts(inputBitDepth: Int, usbBytesPerSample: Int, usbBitResolution: Int): Boolean {
        if (inputBitDepth !in setOf(16, 32)) return false
        return usbBytesPerSample == inputBitDepth / 8 && usbBitResolution == inputBitDepth
    }
}
