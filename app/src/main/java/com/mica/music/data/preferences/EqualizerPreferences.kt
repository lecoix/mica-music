package com.mica.music.data.preferences

import android.content.Context
import com.mica.music.media.eq.EqBandConstants

/** 均衡器开关、预设索引、频段与全局增益偏好。 */
object EqualizerPreferences {
    private const val KEY_EQUALIZER_ENABLED = "equalizer_enabled"
    private const val KEY_EQUALIZER_PRESET = "equalizer_preset"
    private const val KEY_EQUALIZER_BAND_LEVELS = "equalizer_band_levels"
    private const val KEY_EQUALIZER_GLOBAL_GAIN = "equalizer_global_gain"

    /** [equalizerPresetIndex] 为自定义频段时的占位值 */
    const val EQ_PRESET_CUSTOM = -1

    fun equalizerEnabled(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_EQUALIZER_ENABLED, false)

    fun setEqualizerEnabled(context: Context, enabled: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_EQUALIZER_ENABLED, enabled)
            .apply()
    }

    fun equalizerPresetIndex(context: Context): Int =
        MicaSettingsStore.prefs(context).getInt(KEY_EQUALIZER_PRESET, 0)

    fun setEqualizerPresetIndex(context: Context, index: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(KEY_EQUALIZER_PRESET, index)
            .apply()
    }

    fun equalizerBandLevels(context: Context): List<Short> =
        MicaSettingsStore.prefs(context).getString(KEY_EQUALIZER_BAND_LEVELS, null)
            ?.split(',')
            ?.mapNotNull { it.toShortOrNull() }
            ?: emptyList()

    fun setEqualizerBandLevels(context: Context, levels: List<Short>) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_EQUALIZER_BAND_LEVELS, levels.joinToString(","))
            .apply()
    }

    fun equalizerGlobalGainMillibels(context: Context): Short =
        MicaSettingsStore.prefs(context)
            .getInt(KEY_EQUALIZER_GLOBAL_GAIN, EqBandConstants.DEFAULT_GLOBAL_GAIN_MILLIBELS.toInt())
            .coerceIn(
                EqBandConstants.MIN_GLOBAL_GAIN_MILLIBELS.toInt(),
                EqBandConstants.MAX_GLOBAL_GAIN_MILLIBELS.toInt(),
            )
            .toShort()

    fun setEqualizerGlobalGainMillibels(context: Context, gainMillibels: Short) {
        val clamped = gainMillibels.coerceIn(
            EqBandConstants.MIN_GLOBAL_GAIN_MILLIBELS,
            EqBandConstants.MAX_GLOBAL_GAIN_MILLIBELS,
        )
        MicaSettingsStore.prefs(context).edit()
            .putInt(KEY_EQUALIZER_GLOBAL_GAIN, clamped.toInt())
            .apply()
    }
}
