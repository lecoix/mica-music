package com.mica.music.data.preferences

import android.content.Context

/** 睡眠定时选择器的最近一次设置。 */
object SleepTimerPreferences {
    private const val KEY_LAST_DURATION_MINUTES = "sleep_timer_last_duration_minutes"
    private const val KEY_EXTEND_TO_TRACK_END = "sleep_timer_extend_to_track_end"
    const val DEFAULT_DURATION_MINUTES = 30

    fun lastDurationMinutes(context: Context): Int =
        MicaSettingsStore.prefs(context).getInt(
            KEY_LAST_DURATION_MINUTES,
            DEFAULT_DURATION_MINUTES,
        )

    fun setLastDurationMinutes(context: Context, minutes: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(KEY_LAST_DURATION_MINUTES, minutes)
            .apply()
    }

    fun extendToTrackEnd(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_EXTEND_TO_TRACK_END, false)

    fun setExtendToTrackEnd(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_EXTEND_TO_TRACK_END, enabled)
            .apply()
    }
}
