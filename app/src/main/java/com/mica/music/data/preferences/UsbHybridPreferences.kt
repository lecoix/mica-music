package com.mica.music.data.preferences

import android.content.Context

enum class UsbHybridOutputMode {
    SharedPcm,
    ExactPcm,
    Dop,
    NativeDsdExperimental,
}

enum class UsbHybridVolumeControlMode {
    Auto,
    Dac,
    Digital,
    Raw,
}
object UsbHybridPreferences {
    private const val KEY_OUTPUT_MODE = "usb_hybrid_output_mode"
    private const val KEY_RETRY_REVISION = "usb_hybrid_retry_revision"
    private const val KEY_NATIVE_ACKNOWLEDGED = "usb_hybrid_native_acknowledged"
    private const val KEY_VOLUME_CONTROL_MODE = "usb_hybrid_volume_control_mode"
    private const val KEY_DSD_GAIN_COMPENSATION_DB = "usb_hybrid_dsd_gain_compensation_db"
    private const val KEY_VOLUME_SMOOTH_HANDOFF = "usb_hybrid_volume_smooth_handoff"

    fun outputMode(context: Context): UsbHybridOutputMode {
        val stored = MicaSettingsStore.prefs(context).getString(KEY_OUTPUT_MODE, null)
        return UsbHybridOutputMode.entries.firstOrNull { it.name == stored }
            ?: UsbHybridOutputMode.SharedPcm
    }

    fun setOutputMode(context: Context, mode: UsbHybridOutputMode) {
        MicaSettingsStore.prefs(context).edit().putString(KEY_OUTPUT_MODE, mode.name).apply()
    }

    fun volumeControlMode(context: Context): UsbHybridVolumeControlMode {
        val stored = MicaSettingsStore.prefs(context).getString(KEY_VOLUME_CONTROL_MODE, null)
        return UsbHybridVolumeControlMode.entries.firstOrNull { it.name == stored }
            ?: UsbHybridVolumeControlMode.Raw
    }

    fun setVolumeControlMode(context: Context, mode: UsbHybridVolumeControlMode) {
        MicaSettingsStore.prefs(context).edit().putString(KEY_VOLUME_CONTROL_MODE, mode.name).apply()
    }

    fun dsdGainCompensationDb(context: Context): Int =
        MicaSettingsStore.prefs(context).getInt(KEY_DSD_GAIN_COMPENSATION_DB, 0).coerceIn(-12, 6)

    fun setDsdGainCompensationDb(context: Context, value: Int) {
        MicaSettingsStore.prefs(context).edit().putInt(KEY_DSD_GAIN_COMPENSATION_DB, value.coerceIn(-12, 6)).apply()
    }

    fun volumeSmoothHandoff(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_VOLUME_SMOOTH_HANDOFF, true)

    fun setVolumeSmoothHandoff(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit().putBoolean(KEY_VOLUME_SMOOTH_HANDOFF, enabled).apply()
    }
    fun requestRetry(context: Context) {
        val preferences = MicaSettingsStore.prefs(context)
        preferences.edit()
            .putLong(KEY_RETRY_REVISION, preferences.getLong(KEY_RETRY_REVISION, 0L) + 1L)
            .apply()
    }

    fun nativeAcknowledged(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_NATIVE_ACKNOWLEDGED, false)

    fun acknowledgeNative(context: Context) {
        MicaSettingsStore.prefs(context).edit().putBoolean(KEY_NATIVE_ACKNOWLEDGED, true).apply()
    }

    fun registerChangeListener(
        context: Context,
        onChanged: (UsbHybridOutputMode) -> Unit,
    ): () -> Unit {
        val preferences = MicaSettingsStore.prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_OUTPUT_MODE || key == KEY_RETRY_REVISION) onChanged(outputMode(context))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
