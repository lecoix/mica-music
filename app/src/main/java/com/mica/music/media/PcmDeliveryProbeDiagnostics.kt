package com.mica.music.media

import android.content.Context
import androidx.media3.common.PlaybackParameters
import com.mica.music.data.Song
import com.mica.music.data.preferences.EqualizerPreferences
import com.mica.music.data.preferences.SoundFxPreferences
import com.mica.music.util.DiagnosticLog

/** Gate 3-0: logs delivery probe results (release + debug). */
internal object PcmDeliveryProbeDiagnostics {

    fun logForSong(
        context: Context,
        song: Song,
        playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT,
    ) {
        val result = PcmDeliveryProbe.probe(
            context = context.applicationContext,
            song = song,
            dspPathActive = dspPathActive(context, playbackParameters),
        )
        logResult(result)
    }

    private fun dspPathActive(context: Context, playbackParameters: PlaybackParameters): Boolean =
        EqualizerPreferences.equalizerEnabled(context) ||
            SoundFxPreferences.isDspActive(context) ||
            playbackParameters.speed != 1f ||
            playbackParameters.pitch != 1f

    private fun logResult(result: PcmDeliveryProbeResult) {
        val route = result.route
        DiagnosticLog.event(
            "PcmDeliveryProbe",
            "song=${result.songId} route=${route.deviceName} type=${route.deviceType} " +
                "bluetooth=${route.bluetooth} usb=${route.usb} " +
                "source=${result.sourceFormat.sampleRateHz}/${result.sourceFormat.channelCount}/" +
                "${result.sourceFormat.bitsPerSample} isDsd=${result.isDsd} " +
                "dspActive=${result.dspPathActive} " +
                "noDspLadder=${ladderLabel(result.noDspLadder)} " +
                "dspLadder=${ladderLabel(result.dspLadder)} " +
                "selectedNoDsp=${result.selectedNoDsp?.label() ?: "n/a"} " +
                "selectedDsp=${result.selectedDsp?.label() ?: "n/a"} " +
                "dsdIntCandidates=${dsdCandidatesLabel(result.dsdIntCandidates)}",
        )
    }

    private fun ladderLabel(steps: List<PcmDeliveryLadderStep>): String =
        if (steps.isEmpty()) {
            "n/a"
        } else {
            steps.joinToString(separator = ",") { step ->
                buildString {
                    append(step.format.label())
                    append('=')
                    append(if (step.supported) "yes" else "no")
                    step.directSupport?.let { append("(direct=$it)") }
                }
            }
        }

    private fun dsdCandidatesLabel(candidates: List<AlacPcmFormat>): String =
        if (candidates.isEmpty()) {
            "n/a"
        } else {
            candidates.joinToString { "${it.bitsPerSample}/${it.sampleRateHz}" }
        }
}
