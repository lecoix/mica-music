package com.mica.music.data.preferences

import android.content.Context
import com.mica.music.data.AppAccentColor
import com.mica.music.data.AppThemeMode
import com.mica.music.ui.theme.CustomMicaBackground
import com.mica.music.ui.theme.MicaPreset

/** 主题、状态栏、强调色与云母背景偏好。 */
object AppearancePreferences {
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_HIDE_STATUS_BAR = "hide_status_bar"
    /** 旧版 key，迁移到 [KEY_HIDE_STATUS_BAR] */
    private const val KEY_IMMERSIVE_PLAYER_STATUS_BAR = "immersive_player_status_bar"
    private const val KEY_APP_ACCENT_COLOR = "app_accent_color"
    private const val KEY_CUSTOM_ACCENT_COLOR = "custom_accent_color"
    private const val KEY_MICA_BACKGROUND_PRESET = "mica_background_preset"
    private const val KEY_CUSTOM_MICA_START = "custom_mica_start"
    private const val KEY_CUSTOM_MICA_END = "custom_mica_end"
    private const val KEY_CUSTOM_MICA_SINGLE_COLOR = "custom_mica_single_color"

    private const val DEFAULT_CUSTOM_ACCENT_COLOR_ARGB = 0xFF8B7AFF.toInt()
    private const val DEFAULT_CUSTOM_MICA_START_ARGB = CustomMicaBackground.DEFAULT_START_ARGB
    private const val DEFAULT_CUSTOM_MICA_END_ARGB = CustomMicaBackground.DEFAULT_END_ARGB

    fun themeMode(context: Context): AppThemeMode {
        val p = MicaSettingsStore.prefs(context)
        if (!p.contains(KEY_THEME_MODE)) return AppThemeMode.SYSTEM
        return AppThemeMode.fromStorage(p.getString(KEY_THEME_MODE, null))
    }

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_THEME_MODE, mode.storageValue)
            .apply()
    }

    /** 全应用隐藏状态栏（含主页、设置、播放页）；从屏幕边缘下滑可临时显示 */
    fun hideStatusBar(context: Context): Boolean {
        val p = MicaSettingsStore.prefs(context)
        return when {
            p.contains(KEY_HIDE_STATUS_BAR) -> p.getBoolean(KEY_HIDE_STATUS_BAR, false)
            p.contains(KEY_IMMERSIVE_PLAYER_STATUS_BAR) ->
                p.getBoolean(KEY_IMMERSIVE_PLAYER_STATUS_BAR, true)
            else -> false
        }
    }

    fun setHideStatusBar(context: Context, hide: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_HIDE_STATUS_BAR, hide)
            .apply()
    }

    fun appAccentColor(context: Context): AppAccentColor =
        AppAccentColor.fromStorage(MicaSettingsStore.prefs(context).getString(KEY_APP_ACCENT_COLOR, null))

    fun setAppAccentColor(context: Context, accent: AppAccentColor) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_APP_ACCENT_COLOR, accent.storageValue)
            .apply()
    }

    fun customAccentColorArgb(context: Context): Int =
        MicaSettingsStore.prefs(context).getInt(KEY_CUSTOM_ACCENT_COLOR, DEFAULT_CUSTOM_ACCENT_COLOR_ARGB)

    fun setCustomAccentColorArgb(context: Context, colorArgb: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(KEY_CUSTOM_ACCENT_COLOR, colorArgb)
            .apply()
    }

    fun micaBackgroundPreset(context: Context): MicaPreset =
        MicaPreset.fromStorage(MicaSettingsStore.prefs(context).getString(KEY_MICA_BACKGROUND_PRESET, null))

    fun setMicaBackgroundPreset(context: Context, preset: MicaPreset) {
        MicaSettingsStore.prefs(context).edit()
            .putString(KEY_MICA_BACKGROUND_PRESET, preset.storageValue)
            .apply()
    }

    fun customMicaStartArgb(context: Context): Int =
        MicaSettingsStore.prefs(context).getInt(KEY_CUSTOM_MICA_START, DEFAULT_CUSTOM_MICA_START_ARGB)

    fun setCustomMicaStartArgb(context: Context, colorArgb: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(KEY_CUSTOM_MICA_START, colorArgb)
            .apply()
    }

    fun customMicaEndArgb(context: Context): Int =
        MicaSettingsStore.prefs(context).getInt(KEY_CUSTOM_MICA_END, DEFAULT_CUSTOM_MICA_END_ARGB)

    fun setCustomMicaEndArgb(context: Context, colorArgb: Int) {
        MicaSettingsStore.prefs(context).edit()
            .putInt(KEY_CUSTOM_MICA_END, colorArgb)
            .apply()
    }

    fun customMicaSingleColor(context: Context): Boolean =
        MicaSettingsStore.prefs(context).getBoolean(KEY_CUSTOM_MICA_SINGLE_COLOR, false)

    fun setCustomMicaSingleColor(context: Context, singleColor: Boolean) {
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_CUSTOM_MICA_SINGLE_COLOR, singleColor)
            .apply()
    }
}
