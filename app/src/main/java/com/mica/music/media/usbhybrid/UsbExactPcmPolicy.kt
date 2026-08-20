package com.mica.music.media.usbhybrid

import androidx.media3.common.C

object UsbExactPcmPolicy {
    fun bitDepth(pcmEncoding: Int): Int? = when (pcmEncoding) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_32BIT -> 32
        else -> null
    }

    fun speedFailure(speed: Float): UsbFailure? = if (speed == 1f) {
        null
    } else {
        UsbFailure("SPEED_REJECTED", "USB Exact PCM requires playback speed 1.0.")
    }
}
