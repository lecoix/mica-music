package com.mica.music.media

import android.content.Context
import android.media.audiofx.Equalizer
import com.mica.music.data.AppPreferences
import com.mica.music.data.EqCustomProfile
import com.mica.music.data.EqCustomProfileStore
import com.mica.music.data.EqSelection
import com.mica.music.media.eq.EqBandConstants
import com.mica.music.media.eq.EqBandMapper
import com.mica.music.media.eq.SoftwareEqualizer
import com.mica.music.media.eq.SoftwareEqualizerAudioProcessor

/**
 * 均衡器管理：10 段软件 EQ 负责实际音频处理；系统 [Equalizer] 仅用于读取预设名称/曲线。
 */
object MicaEqualizerManager {

    private val softwareEqualizer = SoftwareEqualizer()
    val audioProcessor: SoftwareEqualizerAudioProcessor = SoftwareEqualizerAudioProcessor(softwareEqualizer)

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
    }

    fun processPcmBuffer(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        encoding: Int,
        sampleRateHz: Int,
        channelCount: Int,
    ) {
        softwareEqualizer.configure(sampleRateHz, channelCount)
        softwareEqualizer.processInterleaved(buffer, offset, length, encoding)
    }

    fun snapshot(context: Context): EqualizerSnapshot {
        ensurePreferencesLoaded(context)
        val presets = readSystemPresets()
        return EqualizerSnapshot(
            enabled = AppPreferences.equalizerEnabled(context),
            selection = EqCustomProfileStore.getSelection(context),
            presets = presets,
            savedProfiles = EqCustomProfileStore.listProfiles(context),
            bands = currentBands(),
            levelMinMillibels = EqBandConstants.MIN_MILLIBELS,
            levelMaxMillibels = EqBandConstants.MAX_MILLIBELS,
            sessionReady = attachedSessionId != 0,
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        AppPreferences.setEqualizerEnabled(context, enabled)
        softwareEqualizer.setEnabled(enabled)
        systemEqualizer?.enabled = false
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
        val stored = AppPreferences.equalizerBandLevels(context)
        applyLevels(context, EqBandMapper.normalizeLevels(stored))
    }

    private fun applyLevels(context: Context, levels: ShortArray) {
        softwareEqualizer.setLevels(levels)
        persistCurrentBands(context)
    }

    private fun persistCurrentBands(context: Context) {
        AppPreferences.setEqualizerBandLevels(context, softwareEqualizer.currentLevels().toList())
    }

    private fun ensurePreferencesLoaded(context: Context) {
        if (preferencesLoaded) return
        syncSoftwareFromPreferences(context)
        preferencesLoaded = true
    }

    private fun syncSoftwareFromPreferences(context: Context) {
        softwareEqualizer.setEnabled(AppPreferences.equalizerEnabled(context))
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
            EqualizerPresetOption(index = idx, name = eq.getPresetName(idx.toShort()))
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
