package com.mica.music.media

import android.content.Context
import com.mica.music.BuildConfig
import com.mica.music.media.usb.Sk02UsbContract
import com.mica.music.media.usb.UsbOutputRequest

/**
 * THROWAWAY PROTOTYPE switch. Release builds always resolve to [AudioOutputPathConfig.PRODUCTION].
 * The debug receiver toggles the preference and the service picks it up on its next creation.
 */
internal object UsbHostPrototypeOutput {
    private const val PREFS = "usb_host_prototype"
    private const val ENABLED = "sk02_media3_enabled"

    fun selectedPath(context: Context): AudioOutputPathConfig {
        val enabled = isEnabled(context)
        return if (enabled) {
            AudioOutputPathConfig(
                outputMode = PlaybackOutputMode.UsbDirectPcm,
                usbOutputRequest = UsbOutputRequest(device = Sk02UsbContract.identity),
            )
        } else {
            AudioOutputPathConfig.PRODUCTION
        }
    }

    fun isEnabled(context: Context): Boolean =
        BuildConfig.DEBUG && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        check(BuildConfig.DEBUG) { "USB Host prototype is debug-only" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()
    }
}
