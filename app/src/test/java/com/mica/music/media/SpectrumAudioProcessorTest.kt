package com.mica.music.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumAudioProcessorTest {

    @After
    fun tearDown() {
        MicaSpectrumAnalyzer.setEnabled(false)
    }

    @Test
    fun inactiveWhenSpectrumDisabled() {
        MicaSpectrumAnalyzer.setEnabled(false)
        val processor = SpectrumAudioProcessor()
        val format = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        assertFalse(processor.isActive())
    }

    @Test
    fun passesThroughPcmWhenDisabled() {
        MicaSpectrumAnalyzer.setEnabled(false)
        val processor = SpectrumAudioProcessor()
        val format = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)

        val input = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val buffer = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
        processor.queueInput(buffer)
        val output = processor.getOutput()

        val outBytes = ByteArray(output.remaining())
        output.get(outBytes)
        assertArrayEquals(input, outBytes)
    }

    @Test
    fun passesThroughPcmWhenEnabled() {
        MicaSpectrumAnalyzer.setEnabled(true)
        val processor = SpectrumAudioProcessor()
        val format = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        assertTrue(processor.isActive())

        val input = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val buffer = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
        processor.queueInput(buffer)
        val output = processor.getOutput()

        val outBytes = ByteArray(output.remaining())
        output.get(outBytes)
        assertArrayEquals(input, outBytes)
    }
}
