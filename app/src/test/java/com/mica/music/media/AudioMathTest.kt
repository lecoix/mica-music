package com.mica.music.media

import com.mica.music.media.eq.EqBandConstants
import com.mica.music.media.eq.EqBandMapper
import com.mica.music.media.eq.SoftwareEqualizer
import com.mica.music.testutil.SongFixtures
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMathTest {
    @Test
    fun audioOutputCapabilitiesClassifiesBluetoothAndUsbRoutes() {
        assertTrue(AudioOutputCapabilities.isBluetooth(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertFalse(AudioOutputCapabilities.isBluetooth(AudioDeviceInfo.TYPE_USB_DEVICE))

        assertTrue(AudioOutputCapabilities.isUsb(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertTrue(AudioOutputCapabilities.isUsb(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertFalse(AudioOutputCapabilities.isUsb(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }

    @Test
    fun pcmFormatUsesSafeDefaultsAndFrameAlignedOffsets() {
        val song = SongFixtures.song().copy(
            metadata = SongFixtures.song().metadata.copy(
                sampleRateHz = 0,
                bitsPerSample = null,
                channelCount = 8,
            ),
        )
        val format = AlacPcmFormat.fromSong(song)
        assertEquals(44_100, format.sampleRateHz)
        assertEquals(2, format.channelCount)
        assertEquals(16, format.bitsPerSample)
        assertEquals(176_400, format.byteOffsetForMs(1_000))
    }

    @Test
    fun equalizerEmptyAndExactInputsAreDeterministic() {
        assertArrayEquals(
            EqBandConstants.defaultLevels(),
            EqBandMapper.mapToSoftwareBands(emptyList()),
        )
        val exact = EqBandConstants.CENTER_HZ.mapIndexed { index, hz ->
            hz to (index * 100).toShort()
        }
        assertArrayEquals(
            exact.map { it.second }.toShortArray(),
            EqBandMapper.mapToSoftwareBands(exact),
        )
    }

    @Test
    fun fiveBandInputMapsToTenFiniteBands() {
        val mapped = EqBandMapper.normalizeLevels(listOf(-1_000, -500, 0, 500, 1_000))
        assertEquals(EqBandConstants.BAND_COUNT, mapped.size)
        assertTrue(mapped.all { it in -2_000..2_000 })
    }

    @Test
    fun equalizerKeepsStereoFilterStateIndependent() {
        val equalizer = SoftwareEqualizer()
        equalizer.configure(44_100, 2)
        equalizer.setBandLevel(4, 1_200)
        equalizer.setEnabled(true)
        val buffer = ByteBuffer.allocate(4 * 256).order(ByteOrder.LITTLE_ENDIAN)
        repeat(256) { frame ->
            buffer.putShort(if (frame == 0) 20_000 else 0)
            buffer.putShort(0)
        }
        val bytes = buffer.array()

        equalizer.processInterleaved(bytes, 0, bytes.size, AudioFormat.ENCODING_PCM_16BIT)

        val result = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(256) {
            result.short
            assertEquals(0, result.short.toInt())
        }
    }

    @Test
    fun limiterIsMonotonicAndStaysBelowMinusOneDbfs() {
        val equalizer = SoftwareEqualizer()
        equalizer.configure(44_100, 1)
        equalizer.setEnabled(true)
        val samples = floatArrayOf(0.70f, 0.80f, 0.88f, 0.90f, 1.20f)
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(buffer::putFloat)
        val bytes = buffer.array()

        equalizer.processInterleaved(bytes, 0, bytes.size, AudioFormat.ENCODING_PCM_FLOAT)

        val output = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val limited = FloatArray(samples.size) { output.float }
        assertTrue((1 until limited.size).all { limited[it] >= limited[it - 1] })
        val ceiling = Math.pow(10.0, -1.0 / 20.0).toFloat()
        assertTrue(limited.all { it <= ceiling })
    }

    @Test
    fun globalGainScalesSamplesBeforeLimiter() {
        val equalizer = SoftwareEqualizer()
        equalizer.configure(44_100, 1)
        equalizer.setGlobalGainMillibels(600)
        equalizer.setEnabled(true)
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putFloat(0.25f)
        val bytes = buffer.array()

        equalizer.processInterleaved(bytes, 0, bytes.size, AudioFormat.ENCODING_PCM_FLOAT)

        assertEquals(600, equalizer.currentGlobalGainMillibels().toInt())
        assertEquals(0.5f, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).float, 0.01f)
    }

    @Test
    fun dsdOutputPolicyAvoidsUltrahighRatesOnBluetooth() {
        val bluetooth = DsdOutputPolicy.candidates(
            channelCount = 2,
            bluetooth = true,
            supportsPacked24 = true,
        )
        assertTrue(bluetooth.all { it.sampleRateHz == 48_000 })
        assertEquals(listOf(24, 16), bluetooth.map { it.bitsPerSample })

        val wired = DsdOutputPolicy.candidates(
            channelCount = 2,
            bluetooth = false,
            supportsPacked24 = true,
        )
        assertEquals(
            listOf(176_400, 88_200, 88_200, 48_000),
            wired.map { it.sampleRateHz },
        )
    }
}
