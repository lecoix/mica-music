package com.mica.music.data.preferences

import android.content.Context
import com.mica.music.data.ScanSource

/**
 * Internal kill switches for automatic local-library synchronization.
 *
 * These gates are intentionally separate from manual/full-scan controls. A disabled AUTO source
 * must produce no background discovery or authority write, while explicit user scans remain
 * available as the recovery path.
 */
internal object LibraryAutoSyncPreferences {
    private const val KEY_ENABLED = "library_auto_sync_enabled"
    private const val KEY_DEVICE_ENABLED = "library_auto_sync_device_enabled"
    private const val KEY_FOLDER_ENABLED = "library_auto_sync_folder_enabled"

    fun enabled(context: Context, source: ScanSource): Boolean =
        globallyEnabled(context) && sourceEnabled(context, source)

    fun globallyEnabled(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_ENABLED, true)

    fun sourceEnabled(context: Context, source: ScanSource): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(
            when (source) {
                ScanSource.DEVICE -> KEY_DEVICE_ENABLED
                ScanSource.FOLDER -> KEY_FOLDER_ENABLED
            },
            true,
        )

    fun setGloballyEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun setSourceEnabled(
        context: Context,
        source: ScanSource,
        enabled: Boolean,
    ) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(
                when (source) {
                    ScanSource.DEVICE -> KEY_DEVICE_ENABLED
                    ScanSource.FOLDER -> KEY_FOLDER_ENABLED
                },
                enabled,
            )
            .apply()
    }

    internal fun clearOverrides(context: Context) {
        MicaSettingsStore.prefs(context).edit()
            .remove(KEY_ENABLED)
            .remove(KEY_DEVICE_ENABLED)
            .remove(KEY_FOLDER_ENABLED)
            .apply()
    }
}
