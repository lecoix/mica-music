package com.mica.music.media

import android.content.Context
import androidx.media3.common.PlaybackParameters
import com.mica.music.data.Song
import com.mica.music.data.preferences.EqualizerPreferences

/** Gate 3-1b: map probe results to sink delivery config (per song / dsp state). */
internal object PcmSinkDeliveryDecider {

    fun decide(
        context: Context,
        song: Song,
        playbackParameters: PlaybackParameters,
    ): PcmSinkDeliveryConfig {
        if (!PcmDeliveryExperiment.g31bPerSongSink) {
            return PcmSinkDeliveryConfig.PRODUCTION
        }
        val probe = PcmDeliveryProbe.probe(
            context = context.applicationContext,
            song = song,
            dspPathActive = dspPathActive(context, playbackParameters),
        )
        val enableFloatOutput = enableFloatOutput(probe)
        return PcmSinkDeliveryConfig(
            enableFloatOutput = enableFloatOutput,
            profileLabel = profileLabel(probe, enableFloatOutput),
        )
    }

    internal fun enableFloatOutput(probe: PcmDeliveryProbeResult): Boolean {
        if (probe.isDsd) {
            // DsdDecimation emits 24-bit PCM; float sink breaks AudioTrack configure on device
            // (log 28: IllegalStateException in getAudioTrackMinBufferSize after FLAC→DSF auto-advance).
            return false
        }
        if (probe.dspPathActive) {
            return probe.selectedDsp is PcmDeliveryFormat.FloatPcm
        }
        return when (val selected = probe.selectedNoDsp) {
            is PcmDeliveryFormat.FloatPcm -> true
            is PcmDeliveryFormat.IntPcm ->
                selected.bitsPerSample > 16 && probe.selectedDsp is PcmDeliveryFormat.FloatPcm
            null -> false
        }
    }

    private fun dspPathActive(context: Context, playbackParameters: PlaybackParameters): Boolean =
        EqualizerPreferences.equalizerEnabled(context) ||
            playbackParameters.speed != 1f ||
            playbackParameters.pitch != 1f

    private fun profileLabel(probe: PcmDeliveryProbeResult, enableFloatOutput: Boolean): String =
        when {
            !enableFloatOutput && probe.isDsd -> "G3-1b-dsd-int-sink"
            !enableFloatOutput -> "G3-1b-int16-sink"
            probe.dspPathActive -> "G3-1b-dsp-float-sink"
            else -> "G3-1b-pcm-float-sink"
        }
}
