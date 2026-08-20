package com.mica.music.data.preferences

import android.content.Context

enum class UsbHybridOutputMode {
    SharedPcm,
    ExactPcm,
    Dop,
    NativeDsdExperimental,
}

object UsbHybridPreferences {
    private const val KEY_OUTPUT_MODE = "usb_hybrid_output_mode"

    fun outputMode(context: Context): UsbHybridOutputMode {
        val stored = MicaSettingsStore.prefs(context).getString(KEY_OUTPUT_MODE, null)
        return UsbHybridOutputMode.entries.firstOrNull { it.name == stored }
            ?: UsbHybridOutputMode.SharedPcm
    }

    fun setOutputMode(context: Context, mode: UsbHybridOutputMode) {
        MicaSettingsStore.prefs(context).edit().putString(KEY_OUTPUT_MODE, mode.name).apply()
    }

    fun registerChangeListener(
        context: Context,
        onChanged: (UsbHybridOutputMode) -> Unit,
    ): () -> Unit {
        val preferences = MicaSettingsStore.prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_OUTPUT_MODE) onChanged(outputMode(context))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
