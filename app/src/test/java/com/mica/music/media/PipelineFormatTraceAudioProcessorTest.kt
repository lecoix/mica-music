package com.mica.music.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PipelineFormatTraceAudioProcessorTest {

    @Test
    fun configure_logsAndPassthroughFormat() {
        val input = AudioProcessor.AudioFormat(96_000, 2, C.ENCODING_PCM_24BIT)
        val processor = PipelineFormatTraceAudioProcessor("test-trace")

        val output = processor.configure(input)

        assertEquals(input, output)
    }

    @Test
    fun queueInput_passesThroughUnchangedBytes() {
        val processor = PipelineFormatTraceAudioProcessor("test-trace")
        processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        val input = ByteBuffer.allocateDirect(4).apply {
            put(byteArrayOf(1, 2, 3, 4))
            flip()
        }

        processor.queueInput(input)
        val output = processor.getOutput()

        assertEquals(4, output.remaining())
        assertEquals(1, output.get().toInt())
        assertEquals(2, output.get().toInt())
        assertEquals(3, output.get().toInt())
        assertEquals(4, output.get().toInt())
    }

    @Test
    fun chain_includesChainExitTrace() {
        val chain = MicaAudioProcessorChain(
            PipelineFormatTraceAudioProcessor("sink-entry"),
            includeFormatTrace = true,
        )
        val names = chain.processorNamesForDiagnostics()

        assertEquals(listOf("sink-entry", "PlaybackTuning", "chain-exit"), names)
        assertSame(
            PipelineFormatTraceAudioProcessor::class.java,
            chain.getAudioProcessors().last()::class.java,
        )
    }
}
