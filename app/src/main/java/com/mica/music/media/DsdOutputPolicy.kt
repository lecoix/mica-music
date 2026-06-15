package com.mica.music.media

import android.content.Context
import android.os.Build
import com.mica.music.util.DiagnosticLog

internal object DsdOutputPolicy {

    fun candidates(
        channelCount: Int,
        bluetooth: Boolean,
        supportsPacked24: Boolean,
    ): List<AlacPcmFormat> {
        val channels = channelCount.coerceIn(1, 2)
        if (bluetooth) {
            return buildList {
                if (supportsPacked24) {
                    add(AlacPcmFormat(48_000, channels, 24))
                }
                add(AlacPcmFormat(48_000, channels, 16))
            }
        }
        return buildList {
            if (supportsPacked24) {
                add(AlacPcmFormat(176_400, channels, 24))
                add(AlacPcmFormat(88_200, channels, 24))
            }
            add(AlacPcmFormat(88_200, channels, 16))
            add(AlacPcmFormat(48_000, channels, 16))
        }
    }

    fun candidates(context: Context, channelCount: Int): List<AlacPcmFormat> =
        AudioOutputCapabilities.route(context).let { route ->
            val candidates = candidates(
            channelCount = channelCount,
            bluetooth = route.bluetooth,
            supportsPacked24 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            ).filter { AudioOutputCapabilities.supports(context, it) }
            DiagnosticLog.event(
                "DsdOutput",
                "route=${route.deviceName} type=${route.deviceType} bluetooth=${route.bluetooth} " +
                    "usb=${route.usb} candidates=${candidates.joinToString { format ->
                        "${format.bitsPerSample}/${format.sampleRateHz}"
                    }}",
            )
            candidates
        }
}
