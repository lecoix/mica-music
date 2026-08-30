package com.mica.music.data.preferences

import android.content.Context
import com.mica.music.audio.fx.SoundFxSettings

/** 音效实验室：立体声宽度、低频/高频 shelf、混响、360° 环绕。默认湿比与环绕强度为 0。 */
object SoundFxPreferences {
    private const val KEY_ENABLED = "sound_fx_enabled"
    private const val KEY_STEREO_WIDTH = "sound_fx_stereo_width_percent"
    private const val KEY_BASS_DB = "sound_fx_bass_db"
    private const val KEY_TREBLE_DB = "sound_fx_treble_db"
    private const val KEY_REVERB_ROOM = "sound_fx_reverb_room_percent"
    private const val KEY_REVERB_DAMPING = "sound_fx_reverb_damping_percent"
    private const val KEY_REVERB_WET = "sound_fx_reverb_wet_percent"
    private const val KEY_REVERB_PRESET = "sound_fx_reverb_preset"
    private const val KEY_SURROUND_INTENSITY = "sound_fx_surround_intensity_percent"
    private const val KEY_SURROUND_ROTATION = "sound_fx_surround_rotation_deg_per_sec"

    fun settings(context: Context): SoundFxSettings {
        val prefs = MicaSettingsStore.prefs(context)
        return SoundFxSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            stereoWidthPercent = prefs.getInt(KEY_STEREO_WIDTH, SoundFxSettings.NEUTRAL_WIDTH_PERCENT),
            bassDb = prefs.getInt(KEY_BASS_DB, 0),
            trebleDb = prefs.getInt(KEY_TREBLE_DB, 0),
            reverbRoomPercent = prefs.getInt(
                KEY_REVERB_ROOM,
                SoundFxSettings.DEFAULT_REVERB_ROOM_PERCENT,
            ),
            reverbDampingPercent = prefs.getInt(
                KEY_REVERB_DAMPING,
                SoundFxSettings.DEFAULT_REVERB_DAMPING_PERCENT,
            ),
            reverbWetPercent = prefs.getInt(KEY_REVERB_WET, 0),
            surroundIntensityPercent = prefs.getInt(KEY_SURROUND_INTENSITY, 0),
            surroundRotationDegPerSec = prefs.getInt(
                KEY_SURROUND_ROTATION,
                SoundFxSettings.DEFAULT_SURROUND_ROTATION,
            ),
        ).sanitized()
    }

    fun save(context: Context, settings: SoundFxSettings) {
        val sanitized = settings.sanitized()
        MicaSettingsStore.prefs(context).edit()
            .putBoolean(KEY_ENABLED, sanitized.enabled)
            .putInt(KEY_STEREO_WIDTH, sanitized.stereoWidthPercent)
            .putInt(KEY_BASS_DB, sanitized.bassDb)
            .putInt(KEY_TREBLE_DB, sanitized.trebleDb)
            .putInt(KEY_REVERB_ROOM, sanitized.reverbRoomPercent)
            .putInt(KEY_REVERB_DAMPING, sanitized.reverbDampingPercent)
            .putInt(KEY_REVERB_WET, sanitized.reverbWetPercent)
            .putInt(KEY_SURROUND_INTENSITY, sanitized.surroundIntensityPercent)
            .putInt(KEY_SURROUND_ROTATION, sanitized.surroundRotationDegPerSec)
            .remove(KEY_REVERB_PRESET)
            .apply()
    }

    fun isDspActive(context: Context): Boolean = settings(context).isDspActive()
}
