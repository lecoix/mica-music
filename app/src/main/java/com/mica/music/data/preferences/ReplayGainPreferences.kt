package com.mica.music.data.preferences

import android.content.Context
import com.mica.music.data.ReplayGainMode

object ReplayGainPreferences {
    internal const val KEY_MODE = "replaygain_mode"

    fun mode(context: Context): ReplayGainMode =
        ReplayGainMode.fromStorage(MicaSettingsStore.prefs(context).getString(KEY_MODE, null))

    fun setMode(context: Context, mode: ReplayGainMode) {
        MicaSettingsStore.prefs(context).edit().putString(KEY_MODE, mode.storageValue).apply()
    }
}
