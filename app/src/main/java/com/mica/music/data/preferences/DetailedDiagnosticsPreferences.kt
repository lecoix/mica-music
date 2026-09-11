package com.mica.music.data.preferences

import android.content.Context
import com.mica.music.util.DiagnosticDetailConfig

/** Persistent opt-in for verbose diagnostics. All detailed domains are off by default. */
object DetailedDiagnosticsPreferences {
    private const val KEY_ENABLED = "detailed_diagnostics_enabled"
    private const val KEY_LIBRARY_SCAN = "detailed_diagnostics_library_scan"
    private const val KEY_PLAYBACK_MEDIA = "detailed_diagnostics_playback_media"
    private const val KEY_AUDIO_PIPELINE = "detailed_diagnostics_audio_pipeline"
    private const val KEY_USB_DEVICE = "detailed_diagnostics_usb_device"
    private const val KEY_UI_RENDERING = "detailed_diagnostics_ui_rendering"

    fun state(context: Context): DiagnosticDetailConfig {
        val prefs = MicaSettingsStore.prefs(context)
        return DiagnosticDetailConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            libraryScan = prefs.getBoolean(KEY_LIBRARY_SCAN, false),
            playbackMedia = prefs.getBoolean(KEY_PLAYBACK_MEDIA, false),
            audioPipeline = prefs.getBoolean(KEY_AUDIO_PIPELINE, false),
            usbDevice = prefs.getBoolean(KEY_USB_DEVICE, false),
            uiRendering = prefs.getBoolean(KEY_UI_RENDERING, false),
        )
    }

    fun setState(context: Context, state: DiagnosticDetailConfig) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_ENABLED, state.enabled)
            .putBoolean(KEY_LIBRARY_SCAN, state.libraryScan)
            .putBoolean(KEY_PLAYBACK_MEDIA, state.playbackMedia)
            .putBoolean(KEY_AUDIO_PIPELINE, state.audioPipeline)
            .putBoolean(KEY_USB_DEVICE, state.usbDevice)
            .putBoolean(KEY_UI_RENDERING, state.uiRendering)
            .apply()
    }
}
