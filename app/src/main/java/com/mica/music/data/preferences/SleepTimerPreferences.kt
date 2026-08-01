package com.mica.music.data.preferences

import android.content.Context

/** 睡眠定时选择器的最近一次时长。 */
object SleepTimerPreferences {
    private const val KEY_LAST_DURATION_MINUTES = "sleep_timer_last_duration_minutes"
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
}
