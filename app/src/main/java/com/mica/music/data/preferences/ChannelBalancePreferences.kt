package com.mica.music.data.preferences

import android.content.Context

object ChannelBalancePreferences {
    internal const val KEY_BALANCE_PERCENT = "channel_balance_percent"
    const val MIN_PERCENT = -100
    const val MAX_PERCENT = 100
    const val CENTER = 0

    fun balancePercent(context: Context): Int =
        MicaSettingsStore.prefs(context)
            .getInt(KEY_BALANCE_PERCENT, CENTER)
            .coerceIn(MIN_PERCENT, MAX_PERCENT)

    fun setBalancePercent(context: Context, value: Int) {
        MicaSettingsStore.prefs(context)
            .edit()
            .putInt(KEY_BALANCE_PERCENT, value.coerceIn(MIN_PERCENT, MAX_PERCENT))
            .apply()
    }
}
