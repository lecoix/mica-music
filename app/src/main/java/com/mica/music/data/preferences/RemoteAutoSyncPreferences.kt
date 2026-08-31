package com.mica.music.data.preferences

import android.content.Context

internal object RemoteAutoSyncPreferences {
    private const val KEY_ENABLED = "remote_auto_sync_enabled"

    fun enabled(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}