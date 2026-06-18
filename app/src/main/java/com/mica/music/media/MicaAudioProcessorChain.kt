package com.mica.music.media

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Exo PCM pipeline for Mica: [DsdDecimationAudioProcessor] and EQ only.
 *
 * Omits Media3's default tail processors:
 * - [androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor] rejects packed 24-bit PCM.
 * - [androidx.media3.common.audio.SonicAudioProcessor] only accepts 16-bit and float.
 *
 * Playback speed/pitch should use [DefaultAudioSink.Builder.setEnableAudioOutputPlaybackParameters].
 */
@UnstableApi
class MicaAudioProcessorChain(
    vararg processors: AudioProcessor,
) : DefaultAudioSink.AudioProcessorChain {

    private val audioProcessors: Array<AudioProcessor> = arrayOf(*processors)

    override fun getAudioProcessors(): Array<AudioProcessor> = audioProcessors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters =
        playbackParameters

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = skipSilenceEnabled

    override fun getMediaDuration(playoutDuration: Long): Long = playoutDuration

    override fun getSkippedOutputFrameCount(): Long = 0L
}
