package com.mica.music.media

import com.mica.music.audio.eq.MicaEqualizerManager
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MicaAudioProcessorChainTest {

    @Test
    fun omitsPlaybackTuningWhenDisabled() {
        val dsd = DsdDecimationAudioProcessor(RuntimeEnvironment.getApplication())
        val chain = MicaAudioProcessorChain(
            dsd,
            includePlaybackTuning = false,
            includeFormatTrace = true,
        )
        val processors = chain.getAudioProcessors()

        assertEquals(2, processors.size)
        assertSame(dsd, processors[0])
        assertTrue(processors[1] is PipelineFormatTraceAudioProcessor)
    }

    @Test
    fun omitsFormatTraceWhenDisabled() {
        val dsd = DsdDecimationAudioProcessor(RuntimeEnvironment.getApplication())
        val chain = MicaAudioProcessorChain(
            dsd,
            includePlaybackTuning = true,
            includeFormatTrace = false,
        )
        val processors = chain.getAudioProcessors()

        assertEquals(2, processors.size)
        assertSame(dsd, processors[0])
        assertTrue(processors[1] is MicaPlaybackTuningAudioProcessor)
    }

    @Test
    fun exposesOnlyConfiguredProcessors() {
        val dsd = DsdDecimationAudioProcessor(RuntimeEnvironment.getApplication())
        val eq = MicaEqualizerManager.audioProcessor
        val chain = MicaAudioProcessorChain(
            dsd,
            eq,
            includeFormatTrace = true,
        )
        val processors = chain.getAudioProcessors()

        assertEquals(4, processors.size)
        assertSame(dsd, processors[0])
        assertSame(eq, processors[1])
        assertTrue(processors[2] is MicaPlaybackTuningAudioProcessor)
        assertTrue(processors[3] is PipelineFormatTraceAudioProcessor)
    }

    @Test
    fun delegatesPlaybackParametersToSonic() {
        val chain = MicaAudioProcessorChain()
        val parameters = PlaybackParameters(1.25f)

        assertEquals(parameters, chain.applyPlaybackParameters(parameters))
        assertEquals(true, chain.applySkipSilenceEnabled(true))
        assertEquals(0L, chain.getSkippedOutputFrameCount())
    }

    @Test
    fun playbackTuningProcessor_activatesSonicForSupportedPcm() {
        val processor = MicaPlaybackTuningAudioProcessor()
        processor.setSpeed(1.25f)

        processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))

        assertTrue(processor.isActive)
    }

    @Test
    fun playbackTuningProcessor_passesThroughUnsupportedPcm() {
        val processor = MicaPlaybackTuningAudioProcessor()
        val dsdOutputFormat = AudioProcessor.AudioFormat(176_400, 2, C.ENCODING_PCM_24BIT)
        val input = ByteBuffer.allocateDirect(6).apply {
            put(byteArrayOf(1, 2, 3, 4, 5, 6))
            flip()
        }

        assertEquals(dsdOutputFormat, processor.configure(dsdOutputFormat))
        processor.setSpeed(1.25f)
        processor.queueInput(input)
        val output = processor.output

        assertFalse(processor.isActive)
        assertEquals(6, output.remaining())
        assertEquals(1, output.get().toInt())
        assertEquals(2, output.get().toInt())
        assertEquals(3, output.get().toInt())
    }
}
