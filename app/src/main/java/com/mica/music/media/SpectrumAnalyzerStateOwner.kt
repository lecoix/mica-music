package com.mica.music.media

import android.content.Context
import android.content.SharedPreferences
import com.mica.music.data.preferences.MicaSettingsStore
import com.mica.music.data.preferences.PlaybackUiPreferences
import com.mica.music.util.DiagnosticLog

internal class SpectrumAnalyzerStateOwner(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = MicaSettingsStore.prefs(appContext)
    private var started = false

    var currentEnabled: Boolean = false
        private set

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PlaybackUiPreferences.KEY_SPECTRUM_ENABLED ||
            key == PlaybackUiPreferences.KEY_MINI_PLAYER_STYLE ||
            key == PlaybackUiPreferences.KEY_PLAYER_COVER_FLOW_MODE
        ) {
            apply(notifyPipeline = true)
        }
    }

    fun start() {
        if (started) return
        started = true
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        apply(notifyPipeline = false)
    }

    fun release() {
        if (!started) return
        started = false
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    }

    private fun apply(notifyPipeline: Boolean) {
        currentEnabled = PlaybackUiPreferences.spectrumTapEnabled(appContext)
        MicaSpectrumAnalyzer.setEnabled(currentEnabled, notifyPipeline = notifyPipeline)
        DiagnosticLog.event(
            "Spectrum",
            "preference-applied enabled=$currentEnabled notifyPipeline=$notifyPipeline",
        )
    }
}
