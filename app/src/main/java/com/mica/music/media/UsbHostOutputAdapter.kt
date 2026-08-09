package com.mica.music.media

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutputProvider
import com.mica.music.BuildConfig
import com.mica.music.media.usb.Sk02UsbContract

/** Production output-adapter seam. P1 exposes only the internal SK02 debug implementation. */
internal object UsbHostOutputAdapter {
    private const val SK02_PROVIDER_CLASS =
        "com.mica.music.media.usbprototype.UsbSk02AudioOutputProvider"

    @UnstableApi
    fun createProvider(context: Context, outputPath: AudioOutputPathConfig): AudioOutputProvider {
        check(BuildConfig.DEBUG && outputPath.usbOutputRequest?.device == Sk02UsbContract.identity) {
            "P1 USB Host output is available only to the explicit internal SK02 request"
        }
        return try {
            val constructor = Class.forName(SK02_PROVIDER_CLASS).getConstructor(Context::class.java)
            constructor.newInstance(context.applicationContext) as AudioOutputProvider
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException("Internal SK02 AudioOutputProvider is unavailable", error)
        }
    }
}
