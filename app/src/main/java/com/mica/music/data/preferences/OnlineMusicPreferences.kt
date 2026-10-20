package com.mica.music.data.preferences

import android.content.Context

enum class OnlineMusicDefaultQuality(val value: Int, val label: String) {
    ASK(0, "每次询问"),
    K128(1, "128k"),
    K320(2, "320k"),
    LOSSLESS(3, "无损"),
    ;

    companion object {
        fun fromValue(value: Int): OnlineMusicDefaultQuality = entries.firstOrNull { it.value == value } ?: ASK
    }
}

object OnlineMusicPreferences {
    private const val KEY_DEFAULT_QUALITY = "online_music_default_quality"

    fun defaultQuality(context: Context): OnlineMusicDefaultQuality =
        OnlineMusicDefaultQuality.fromValue(MicaSettingsStore.prefs(context).getInt(KEY_DEFAULT_QUALITY, OnlineMusicDefaultQuality.ASK.value))

    fun setDefaultQuality(context: Context, value: OnlineMusicDefaultQuality) {
        MicaSettingsStore.prefs(context).edit().putInt(KEY_DEFAULT_QUALITY, value.value).apply()
    }

    /** Returns null when the UI must ask instead of silently changing the requested quality. */
    fun resolveDefaultQuality(
        preference: OnlineMusicDefaultQuality,
        available: Collection<String>,
    ): String? = when (preference) {
        OnlineMusicDefaultQuality.ASK -> null
        OnlineMusicDefaultQuality.K128 -> "128k".takeIf(available::contains)
        OnlineMusicDefaultQuality.K320 -> "320k".takeIf(available::contains)
        OnlineMusicDefaultQuality.LOSSLESS -> when {
            "flac" in available -> "flac"
            "flac24bit" in available -> "flac24bit"
            else -> null
        }
    }
}
