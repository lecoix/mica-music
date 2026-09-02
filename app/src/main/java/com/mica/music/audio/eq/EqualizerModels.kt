package com.mica.music.audio.eq

import com.mica.music.data.EqCustomProfile
import com.mica.music.data.EqSelection

data class EqualizerSnapshot(
    val enabled: Boolean,
    val selection: EqSelection,
    val presets: List<EqualizerPresetOption>,
    val savedProfiles: List<EqCustomProfile>,
    val bands: List<EqualizerBand>,
    val globalGainMillibels: Short,
    val globalGainMinMillibels: Short,
    val globalGainMaxMillibels: Short,
    val levelMinMillibels: Short,
    val levelMaxMillibels: Short,
    val sessionReady: Boolean,
)

data class EqualizerPresetOption(
    val index: Int,
    val name: String,
)

data class EqualizerBand(
    val centerHz: Int,
    val levelMillibels: Short,
)