package com.mica.music.media

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutputProvider
import com.mica.music.BuildConfig

/**
 * THROWAWAY PROTOTYPE switch. Release builds always resolve to [AudioOutputPathConfig.PRODUCTION].
 * The debug receiver toggles the preference and the service picks it up on its next creation.
 */
internal object UsbHostPrototypeOutput {
    private const val PREFS = "usb_host_prototype"
    private const val ENABLED = "sk02_media3_enabled"
    private const val PROVIDER_CLASS =
        "com.mica.music.media.usbprototype.UsbSk02AudioOutputProvider"

    fun selectedPath(context: Context): AudioOutputPathConfig {
        val enabled = BuildConfig.DEBUG && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, false)
        return if (enabled) {
            AudioOutputPathConfig(
                outputMode = PlaybackOutputMode.UsbDirectPcm,
                prototypeUsbHost = true,
            )
        } else {
            AudioOutputPathConfig.PRODUCTION
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        check(BuildConfig.DEBUG) { "USB Host prototype is debug-only" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()
    }

    @UnstableApi
    fun createProvider(context: Context, outputPath: AudioOutputPathConfig): AudioOutputProvider {
        check(BuildConfig.DEBUG && outputPath.prototypeUsbHost) {
            "USB Host AudioOutputProvider is only available to the explicit debug prototype"
        }
        return try {
            val constructor = Class.forName(PROVIDER_CLASS).getConstructor(Context::class.java)
            constructor.newInstance(context.applicationContext) as AudioOutputProvider
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException("Debug SK02 AudioOutputProvider is unavailable", error)
        }
    }
}
