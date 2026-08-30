package com.mica.music.media

import android.content.Context
import android.media.audiofx.Equalizer
import androidx.media3.common.util.UnstableApi
import com.mica.music.data.preferences.ChannelBalancePreferences
import com.mica.music.data.preferences.EqualizerPreferences
import com.mica.music.data.preferences.SoundFxPreferences
import com.mica.music.audio.fx.SoundFxSettings
import com.mica.music.data.EqCustomProfile
import com.mica.music.data.EqCustomProfileStore
import com.mica.music.data.EqSelection
import com.mica.music.audio.eq.EqBandConstants
import com.mica.music.media.eq.EqBandMapper
import com.mica.music.media.eq.EqPresetLabels
import com.mica.music.media.eq.SoftwareEqualizer
import com.mica.music.media.eq.SoftwareEqualizerAudioProcessor

/**
 * 均衡器管理：10 段软件 EQ 负责实际音频处理；系统 [Equalizer] 仅用于读取预设名称/曲线。
 */
@UnstableApi
object MicaEqualizerManager {

    var onEnabledChanged: ((Boolean) -> Unit)? = null
    var onReplayGainDspActiveChanged: ((Boolean) -> Unit)? = null
    var onChannelBalanceDspActiveChanged: ((Boolean) -> Unit)? = null
    var onSoundFxDspActiveChanged: ((Boolean) -> Unit)? = null

    private val softwareEqualizer = SoftwareEqualizer()
    val audioProcessor: SoftwareEqualizerAudioProcessor = SoftwareEqualizerAudioProcessor(softwareEqualizer)

    /**
     * Shared EQ DSP for sinks that cannot host [audioProcessor] in their processor chain (e.g. the
     * float PcmSink whose float output path Media3 excludes from the custom chain). Reuses the same
     * instance so UI-driven band/enable changes apply everywhere. Safe because renderer-split keeps
     * only one audio renderer active at a time, so the shared biquad state is never interleaved.
     */
    val equalizer: SoftwareEqualizer get() = softwareEqualizer

    /**
     * Fresh Media3 processor bound to the shared [equalizer], for a sink that needs its own chain
     * instance (e.g. the renderer-split DSD int sink) instead of reusing [audioProcessor], whose
     * internal buffers must not be shared across two [androidx.media3.exoplayer.audio.DefaultAudioSink]
     * chains. Band/enable changes still apply everywhere; renderer-split keeps one renderer active at
     * a time so the shared biquad state is never interleaved.
     */
    fun createAudioProcessor(): SoftwareEqualizerAudioProcessor =
        SoftwareEqualizerAudioProcessor(softwareEqualizer)

    private var systemEqualizer: Equalizer? = null
    private var attachedSessionId: Int = 0

    private var preferencesLoaded = false

    fun attach(context: Context, sessionId: Int) {
        if (sessionId == 0) return
        val appCtx = context.applicationContext
        if (attachedSessionId == sessionId && systemEqualizer != null) {
            syncSoftwareFromPreferences(appCtx)
            return
        }
        releaseSystemOnly()
        attachedSessionId = sessionId
        runCatching {
            systemEqualizer = Equalizer(0, sessionId).also { eq ->
                eq.enabled = false
            }
            syncSoftwareFromPreferences(appCtx)
            preferencesLoaded = true
        }.onFailure {
            releaseSystemOnly()
            syncSoftwareFromPreferences(appCtx)
            preferencesLoaded = true
        }
    }

    fun release() {
        releaseSystemOnly()
        softwareEqualizer.setEnabled(false)
        softwareEqualizer.setReplayGain(enabled = false, factor = 1f)
        softwareEqualizer.setChannelBalancePercent(ChannelBalancePreferences.CENTER)
        softwareEqualizer.setSoundFx(SoundFxSettings())
    }

    fun setReplayGain(enabled: Boolean, factor: Float) {
        val wasActive = softwareEqualizer.isReplayGainHostEnabled()
        softwareEqualizer.setReplayGain(enabled, factor)
        val active = softwareEqualizer.isReplayGainHostEnabled()
        if (wasActive != active) onReplayGainDspActiveChanged?.invoke(active)
    }

    fun setChannelBalancePercent(context: Context, percent: Int) {
        val safe = percent.coerceIn(
            ChannelBalancePreferences.MIN_PERCENT,
            ChannelBalancePreferences.MAX_PERCENT,
        )
        val wasActive = softwareEqualizer.channelBalancePercent() != ChannelBalancePreferences.CENTER
        ChannelBalancePreferences.setBalancePercent(context, safe)
        softwareEqualizer.setChannelBalancePercent(safe)
        val active = safe != ChannelBalancePreferences.CENTER
        if (wasActive != active) onChannelBalanceDspActiveChanged?.invoke(active)
    }

    fun soundFxSettings(context: Context): SoundFxSettings {
        ensurePreferencesLoaded(context)
        return softwareEqualizer.soundFxSettings()
    }

    fun applySoundFx(context: Context, settings: SoundFxSettings) {
        val sanitized = settings.sanitized()
        val wasActive = softwareEqualizer.isSoundFxDspActive()
        SoundFxPreferences.save(context, sanitized)
        softwareEqualizer.setSoundFx(sanitized)
        val active = softwareEqualizer.isSoundFxDspActive()
        if (wasActive != active) onSoundFxDspActiveChanged?.invoke(active)
    }

    fun snapshot(context: Context): EqualizerSnapshot {
        ensurePreferencesLoaded(context)
        val presets = readSystemPresets()
        return EqualizerSnapshot(
            enabled = EqualizerPreferences.equalizerEnabled(context),
            selection = EqCustomProfileStore.getSelection(context),
            presets = presets,
            savedProfiles = EqCustomProfileStore.listProfiles(context),
            bands = currentBands(),
            globalGainMillibels = softwareEqualizer.currentGlobalGainMillibels(),
            globalGainMinMillibels = EqBandConstants.MIN_GLOBAL_GAIN_MILLIBELS,
            globalGainMaxMillibels = EqBandConstants.MAX_GLOBAL_GAIN_MILLIBELS,
            levelMinMillibels = EqBandConstants.MIN_MILLIBELS,
            levelMaxMillibels = EqBandConstants.MAX_MILLIBELS,
            sessionReady = attachedSessionId != 0,
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        EqualizerPreferences.setEqualizerEnabled(context, enabled)
        softwareEqualizer.setEnabled(enabled)
        systemEqualizer?.enabled = false
        onEnabledChanged?.invoke(enabled)
    }

    fun applySelection(context: Context, selection: EqSelection) {
        EqCustomProfileStore.setSelection(context, selection)
        when (selection) {
            is EqSelection.System -> applySystemPreset(context, selection.index)
            EqSelection.Draft -> restoreCustomBands(context)
            is EqSelection.Saved -> {
                val profile = EqCustomProfileStore.findProfile(context, selection.name) ?: return
                applyLevels(context, EqBandMapper.normalizeLevels(profile.levels))
            }
        }
    }

    fun usePreset(context: Context, presetIndex: Int) {
        applySelection(context, EqSelection.System(presetIndex))
    }

    fun setBandLevel(context: Context, bandIndex: Int, levelMillibels: Short) {
        if (bandIndex !in 0 until EqBandConstants.BAND_COUNT) return
        val clamped = levelMillibels.coerceIn(EqBandConstants.MIN_MILLIBELS, EqBandConstants.MAX_MILLIBELS)
        softwareEqualizer.setBandLevel(bandIndex, clamped)
        EqCustomProfileStore.setSelection(context, EqSelection.Draft)
        persistCurrentBands(context)
    }

    fun setGlobalGainMillibels(context: Context, gainMillibels: Short) {
        val clamped = gainMillibels.coerceIn(
            EqBandConstants.MIN_GLOBAL_GAIN_MILLIBELS,
            EqBandConstants.MAX_GLOBAL_GAIN_MILLIBELS,
        )
        softwareEqualizer.setGlobalGainMillibels(clamped)
        EqualizerPreferences.setEqualizerGlobalGainMillibels(context, clamped)
    }

    fun resetFlat(context: Context) {
        applyLevels(context, EqBandConstants.defaultLevels())
        EqCustomProfileStore.setSelection(context, EqSelection.Draft)
    }

    fun saveCurrentAsProfile(context: Context, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val levels = softwareEqualizer.currentLevels().toList()
        EqCustomProfileStore.saveProfile(context, EqCustomProfile(trimmed, levels))
        return true
    }

    fun deleteSavedProfile(context: Context, name: String) {
        EqCustomProfileStore.deleteProfile(context, name)
    }

    private fun applySystemPreset(context: Context, presetIndex: Int) {
        val eq = systemEqualizer
        if (eq != null && presetIndex in 0 until eq.numberOfPresets.toInt()) {
            eq.usePreset(presetIndex.toShort())
            val source = (0 until eq.numberOfBands.toInt()).map { band ->
                (eq.getCenterFreq(band.toShort()) / 1_000) to eq.getBandLevel(band.toShort())
            }
            applyLevels(context, EqBandMapper.mapToSoftwareBands(source))
            eq.enabled = false
            return
        }
        resetFlat(context)
    }

    private fun restoreCustomBands(context: Context) {
        val stored = EqualizerPreferences.equalizerBandLevels(context)
        applyLevels(context, EqBandMapper.normalizeLevels(stored))
    }

    private fun applyLevels(context: Context, levels: ShortArray) {
        softwareEqualizer.setLevels(levels)
        persistCurrentBands(context)
    }

    private fun persistCurrentBands(context: Context) {
        EqualizerPreferences.setEqualizerBandLevels(context, softwareEqualizer.currentLevels().toList())
    }

    private fun ensurePreferencesLoaded(context: Context) {
        if (preferencesLoaded) return
        syncSoftwareFromPreferences(context)
        preferencesLoaded = true
    }

    private fun syncSoftwareFromPreferences(context: Context) {
        val enabled = EqualizerPreferences.equalizerEnabled(context)
        softwareEqualizer.setEnabled(enabled)
        softwareEqualizer.setGlobalGainMillibels(EqualizerPreferences.equalizerGlobalGainMillibels(context))
        softwareEqualizer.setChannelBalancePercent(ChannelBalancePreferences.balancePercent(context))
        softwareEqualizer.setSoundFx(SoundFxPreferences.settings(context))
        when (val selection = EqCustomProfileStore.getSelection(context)) {
            is EqSelection.System -> applySystemPreset(context, selection.index)
            EqSelection.Draft -> restoreCustomBands(context)
            is EqSelection.Saved -> {
                val profile = EqCustomProfileStore.findProfile(context, selection.name)
                if (profile != null) {
                    applyLevels(context, EqBandMapper.normalizeLevels(profile.levels))
                } else {
                    restoreCustomBands(context)
                }
            }
        }
        systemEqualizer?.enabled = false
    }

    private fun currentBands(): List<EqualizerBand> {
        val levels = softwareEqualizer.currentLevels()
        return EqBandConstants.CENTER_HZ.mapIndexed { index, hz ->
            EqualizerBand(centerHz = hz, levelMillibels = levels[index])
        }
    }

    private fun readSystemPresets(): List<EqualizerPresetOption> {
        val eq = systemEqualizer ?: return emptyList()
        return (0 until eq.numberOfPresets.toInt()).map { idx ->
            val systemName = eq.getPresetName(idx.toShort())
            EqualizerPresetOption(
                index = idx,
                name = EqPresetLabels.displayName(idx, systemName),
            )
        }
    }

    private fun releaseSystemOnly() {
        runCatching { systemEqualizer?.release() }
        systemEqualizer = null
        attachedSessionId = 0
    }
}

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
