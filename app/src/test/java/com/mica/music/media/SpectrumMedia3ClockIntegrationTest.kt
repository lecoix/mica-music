package com.mica.music.media

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/** Real Media3 pipeline and Sonic, with only the device AudioOutput replaced by a controlled clock. */
@RunWith(RobolectricTestRunner::class)
class SpectrumMedia3ClockIntegrationTest {
    @After fun cleanup() { MicaSpectrumAnalyzer.setEnabled(false, notifyPipeline = false) }

    @Test fun sonicSpeedAndSeekUseSinkMediaTimeRatherThanOutputDuration() {
        MicaSpectrumAnalyzer.setEnabled(true, notifyPipeline = false)
        var envelope = 0f
        var now = 0L
        val engine = SpectrumAnalysisEngine(nowNanos = { now }, publish = { _, e -> envelope = e })
        engine.setEnabled(true)
        engine.setAnalysisActive(true)
        engine.setPlaybackAdvancing(true)
        val session = SpectrumSinkSession(engine, processorDriven = true)
        val chain = MicaAudioProcessorChain(SpectrumAudioProcessor(session), includeFormatTrace = false)
        val output = mockk<AudioOutput>(relaxed = true)
        var outputPositionUs = 0L
        var writtenBytes = 0
        every { output.getPositionUs() } answers { outputPositionUs }
        every { output.write(any(), any(), any()) } answers {
            val buffer = firstArg<ByteBuffer>()
            writtenBytes += buffer.remaining()
            buffer.position(buffer.limit())
            true
        }
        val provider = mockk<AudioOutputProvider>(relaxed = true)
        every { provider.getFormatSupport(any()) } returns AudioOutputProvider.FormatSupport.Builder()
            .setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY).build()
        every { provider.getOutputConfig(any()) } answers {
            val config = firstArg<AudioOutputProvider.FormatConfig>()
            AudioOutputProvider.OutputConfig.Builder().setEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setBufferSize(192_000).setAudioAttributes(config.audioAttributes).build()
        }
        every { provider.getAudioOutput(any()) } returns output
        val sink = SpectrumClockAudioSink(DefaultAudioSink.Builder(RuntimeEnvironment.getApplication())
            .setAudioProcessorChain(chain).setEnableAudioOutputPlaybackParameters(false)
            .setAudioOutputProvider(provider).build(), session)
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT).setChannelCount(1).setSampleRate(48_000).build()
        sink.configure(format, 0, null)
        sink.setPlaybackParameters(PlaybackParameters(2f))
        sink.play()
        try {
            // Two media seconds: first tone, then silence. Starts after a seek to 10 s.
            val buffer = ByteBuffer.allocateDirect(96_000 * 2).order(ByteOrder.nativeOrder())
            repeat(96_000) { i -> buffer.putShort(if (i < 48_000) (sin(i * 0.13) * 16_000).toInt().toShort() else 0) }
            buffer.flip()
            var accepted = sink.handleBuffer(buffer, 10_000_000, 1)
            repeat(10) { if (!accepted) accepted = sink.handleBuffer(buffer, 10_000_000, 1) }
            assertTrue(accepted)
            sink.playToEndOfStream()
            assertTrue("Sonic must actually shorten output", writtenBytes in 80_000..110_000)
            outputPositionUs = 250_000
            val firstPosition = sink.getCurrentPositionUs(false)
            assertTrue(firstPosition in 10_400_000..10_600_000)
            engine.tick()
            assertTrue(envelope > 0f)
            now += 500_000_000L
            outputPositionUs = 750_000
            val secondPosition = sink.getCurrentPositionUs(false)
            assertTrue(secondPosition in 11_400_000..11_600_000)
            engine.tick()
            assertEquals(0f, envelope)
        } finally { sink.reset() }
    }
}
