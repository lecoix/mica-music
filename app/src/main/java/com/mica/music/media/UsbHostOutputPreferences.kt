package com.mica.music.media

import android.content.Context
import com.mica.music.BuildConfig
import com.mica.music.media.usb.Sk02UsbContract
import com.mica.music.media.usb.UsbOutputRequest

/** Durable, default-off user intent for the release-capable SK02-only backend. */
internal object UsbHostOutputPreferences {
    // Do not inherit the old Debug prototype preference into a production upgrade.
    private const val PREFS = "usb_host_output"
    private const val ENABLED = "sk02_exclusive_enabled_v1"
    private val persistenceLock = Any()

    fun selectedPath(context: Context): AudioOutputPathConfig {
        return pathForEnabled(isEnabled(context))
    }

    fun pathForEnabled(enabled: Boolean): AudioOutputPathConfig =
        if (BuildConfig.USB_EXCLUSIVE_SK02_AVAILABLE && enabled) {
            AudioOutputPathConfig(
                outputMode = PlaybackOutputMode.UsbDirectPcm,
                usbOutputRequest = UsbOutputRequest(device = Sk02UsbContract.identity),
            )
        } else {
            AudioOutputPathConfig.PRODUCTION
        }

    fun isEnabled(context: Context): Boolean = synchronized(persistenceLock) {
        BuildConfig.USB_EXCLUSIVE_SK02_AVAILABLE && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        check(BuildConfig.USB_EXCLUSIVE_SK02_AVAILABLE) { "SK02 USB Host output is unavailable" }
        synchronized(persistenceLock) {
            check(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(ENABLED, enabled)
                    .commit(),
            ) { "Unable to durably persist USB output intent" }
        }
    }
}
