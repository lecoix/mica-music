package com.mica.music.audio.fx

/**
 * Shared-PCM software effects. Defaults are acoustically inactive so HiFi / offload
 * stay available until the user both enables the lab and moves a control off center.
 */
data class SoundFxSettings(
    val enabled: Boolean = false,
    val stereoWidthPercent: Int = NEUTRAL_WIDTH_PERCENT,
    val bassDb: Int = 0,
    val trebleDb: Int = 0,
    val reverbRoomPercent: Int = DEFAULT_REVERB_ROOM_PERCENT,
    val reverbDampingPercent: Int = DEFAULT_REVERB_DAMPING_PERCENT,
    val reverbWetPercent: Int = 0,
    val surroundIntensityPercent: Int = 0,
    val surroundRotationDegPerSec: Int = DEFAULT_SURROUND_ROTATION,
) {
    fun sanitized(): SoundFxSettings = copy(
        stereoWidthPercent = stereoWidthPercent.coerceIn(MIN_WIDTH_PERCENT, MAX_WIDTH_PERCENT),
        bassDb = bassDb.coerceIn(MIN_TONE_DB, MAX_TONE_DB),
        trebleDb = trebleDb.coerceIn(MIN_TONE_DB, MAX_TONE_DB),
        reverbRoomPercent = reverbRoomPercent.coerceIn(MIN_REVERB_PERCENT, MAX_REVERB_PERCENT),
        reverbDampingPercent = reverbDampingPercent.coerceIn(MIN_REVERB_PERCENT, MAX_REVERB_PERCENT),
        reverbWetPercent = reverbWetPercent.coerceIn(MIN_REVERB_PERCENT, MAX_REVERB_PERCENT),
        surroundIntensityPercent = surroundIntensityPercent.coerceIn(
            MIN_SURROUND_PERCENT,
            MAX_SURROUND_PERCENT,
        ),
        surroundRotationDegPerSec = surroundRotationDegPerSec.coerceIn(
            MIN_SURROUND_ROTATION,
            MAX_SURROUND_ROTATION,
        ),
    )

    fun isDspActive(): Boolean {
        if (!enabled) return false
        val settings = sanitized()
        return settings.stereoWidthPercent != NEUTRAL_WIDTH_PERCENT ||
            settings.bassDb != 0 ||
            settings.trebleDb != 0 ||
            settings.reverbWetPercent != 0 ||
            settings.surroundIntensityPercent != 0
    }

    companion object {
        const val MIN_WIDTH_PERCENT = 0
        const val MAX_WIDTH_PERCENT = 200
        const val NEUTRAL_WIDTH_PERCENT = 100
        const val MIN_TONE_DB = -12
        const val MAX_TONE_DB = 12
        const val MIN_REVERB_PERCENT = 0
        const val MAX_REVERB_PERCENT = 100
        const val DEFAULT_REVERB_ROOM_PERCENT = 40
        const val DEFAULT_REVERB_DAMPING_PERCENT = 50
        const val MIN_SURROUND_PERCENT = 0
        const val MAX_SURROUND_PERCENT = 100
        const val MIN_SURROUND_ROTATION = 0
        const val MAX_SURROUND_ROTATION = 90
        const val DEFAULT_SURROUND_ROTATION = 20
    }
}
