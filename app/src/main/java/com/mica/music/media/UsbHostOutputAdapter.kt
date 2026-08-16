package com.mica.music.media

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutputProvider
import com.mica.music.BuildConfig
import com.mica.music.media.usb.Sk02UsbContract
import com.mica.music.media.usb.UsbP2RedemptionContext

/** Production output-adapter seam. The current release-capable implementation is SK02-only. */
internal object UsbHostOutputAdapter {
    private const val SK02_PROVIDER_CLASS =
        "com.mica.music.media.usbprototype.UsbSk02AudioOutputProvider"

    @UnstableApi
    fun createProvider(
        context: Context,
        outputPath: AudioOutputPathConfig,
        redemptionContext: UsbP2RedemptionContext,
    ): AudioOutputProvider {
        check(
            BuildConfig.USB_EXCLUSIVE_SK02_AVAILABLE &&
                outputPath.usbOutputRequest?.device == Sk02UsbContract.identity,
        ) {
            "USB Host output is available only to the explicit supported SK02 request"
        }
        return try {
            val constructor = Class.forName(SK02_PROVIDER_CLASS).getConstructor(
                Context::class.java,
                UsbP2RedemptionContext::class.java,
            )
            constructor.newInstance(context.applicationContext, redemptionContext) as AudioOutputProvider
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException("Internal SK02 AudioOutputProvider is unavailable", error)
        }
    }
}
