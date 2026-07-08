package com.mica.music.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DsdDecimationAudioProcessorTest {

    @Test
    fun resolveTarget_prefersExactDivisorsForDsd64DecoderRate() {
        val context = RuntimeEnvironment.getApplication()
        val target = DsdDecimationAudioProcessor.resolveDsdDecimationTarget(
            context = context,
            inputRateHz = 352_800,
            channelCount = 2,
        )
        assertNotNull(target)
        val (format, factor) = target!!
        assertEquals(176_400, format.sampleRateHz)
        assertEquals(2, factor)
    }

    @Test
    fun configure_downsamplesUltraHighFloatPcm() {
        val processor = DsdDecimationAudioProcessor(RuntimeEnvironment.getApplication())
        val output = processor.configure(
            androidx.media3.common.audio.AudioProcessor.AudioFormat(
                1_411_200,
                2,
                androidx.media3.common.C.ENCODING_PCM_FLOAT,
            ),
        )
        assertEquals(176_400, output.sampleRate)
        assertEquals(2, output.channelCount)
        assertEquals(C.ENCODING_PCM_24BIT, output.encoding)
    }

    @Test
    fun configure_downsamplesHighRateInt16Pcm() {
        val processor = DsdDecimationAudioProcessor(RuntimeEnvironment.getApplication())
        val output = processor.configure(
            AudioProcessor.AudioFormat(
                1_411_200,
                2,
                C.ENCODING_PCM_16BIT,
            ),
        )
        assertEquals(176_400, output.sampleRate)
        assertEquals(2, output.channelCount)
        assertEquals(C.ENCODING_PCM_24BIT, output.encoding)
    }

    @Test
    fun queueInput_decimatesInt16AndWritesPacked24() {
        val processor = DsdDecimationAudioProcessor(RuntimeEnvironment.getApplication())
        processor.configure(AudioProcessor.AudioFormat(352_800, 2, C.ENCODING_PCM_16BIT))
        val input = ByteBuffer.allocateDirect(4 * 2 * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        repeat(4) {
            input.putShort((Short.MAX_VALUE / 2).toShort())
            input.putShort((-Short.MAX_VALUE / 2).toShort())
        }
        input.flip()

        processor.queueInput(input)
        val output = processor.output

        assertEquals(2 * 2 * 3, output.remaining())
    }

    @Test
    fun queueInput_decimatesFloatAndWritesPacked24() {
        val processor = DsdDecimationAudioProcessor(RuntimeEnvironment.getApplication())
        processor.configure(AudioProcessor.AudioFormat(352_800, 2, C.ENCODING_PCM_FLOAT))
        val input = ByteBuffer.allocateDirect(4 * 2 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        repeat(4) {
            input.putFloat(0.5f)
            input.putFloat(-0.5f)
        }
        input.flip()

        processor.queueInput(input)
        val output = processor.output

        assertEquals(2 * 2 * 3, output.remaining())
        assertEquals(0x00, output.get().toInt() and 0xFF)
        assertEquals(0x00, output.get().toInt() and 0xFF)
        assertEquals(0x40, output.get().toInt() and 0xFF)
        assertEquals(0x00, output.get().toInt() and 0xFF)
        assertEquals(0x00, output.get().toInt() and 0xFF)
        assertEquals(0xC0, output.get().toInt() and 0xFF)
    }

    @Test
    fun configure_passesThroughNormalPcm() {
        val processor = DsdDecimationAudioProcessor(RuntimeEnvironment.getApplication())
        val input = androidx.media3.common.audio.AudioProcessor.AudioFormat(
            48_000,
            2,
            androidx.media3.common.C.ENCODING_PCM_FLOAT,
        )
        assertEquals(input, processor.configure(input))
    }

    @Test
    fun configure_floatPcmMode_selectsFloatEncoding() {
        val processor = DsdDecimationAudioProcessor(
            RuntimeEnvironment.getApplication(),
            DsdDecimationOutputMode.FloatPcm,
        )
        val output = processor.configure(
            AudioProcessor.AudioFormat(352_800, 2, C.ENCODING_PCM_FLOAT),
        )
        assertEquals(176_400, output.sampleRate)
        assertEquals(C.ENCODING_PCM_FLOAT, output.encoding)
    }

    @Test
    fun resolveOutputEncoding_mapsModeToMedia3Encoding() {
        val format = AlacPcmFormat(sampleRateHz = 176_400, channelCount = 2, bitsPerSample = 24)
        assertEquals(
            C.ENCODING_PCM_24BIT,
            DsdDecimationAudioProcessor.resolveOutputEncoding(format, DsdDecimationOutputMode.IntPcm),
        )
        assertEquals(
            C.ENCODING_PCM_FLOAT,
            DsdDecimationAudioProcessor.resolveOutputEncoding(format, DsdDecimationOutputMode.FloatPcm),
        )
    }
}
