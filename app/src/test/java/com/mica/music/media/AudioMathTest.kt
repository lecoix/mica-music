package com.mica.music.media

import com.mica.music.audio.eq.EqBandConstants
import com.mica.music.audio.eq.EqBandMapper
import com.mica.music.audio.eq.SoftwareEqualizer
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
    fun fiveBandInputExpandsToPairedUiBands() {
        val mapped = EqBandMapper.normalizeLevels(listOf(-1_000, -500, 0, 500, 1_000))

        assertArrayEquals(
            shortArrayOf(-1_000, -1_000, -500, -500, 0, 0, 500, 500, 1_000, 1_000),
            mapped,
        )
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
    fun flatEqualizerIsTransparentAtFullScale() {
        val equalizer = SoftwareEqualizer()
        equalizer.configure(44_100, 1)
        equalizer.setEnabled(true)
        val samples = floatArrayOf(-1.0f, -0.90f, -0.70f, 0.70f, 0.90f, 1.0f)
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(buffer::putFloat)
        val bytes = buffer.array()
        val original = bytes.copyOf()

        equalizer.processInterleaved(bytes, 0, bytes.size, AudioFormat.ENCODING_PCM_FLOAT)

        assertArrayEquals(original, bytes)
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
    fun replayGainAmplifiesThroughSharedDspWhenEqIsDisabled() {
        val equalizer = SoftwareEqualizer()
        equalizer.configure(44_100, 1)
        equalizer.setReplayGain(enabled = true, factor = 2f)
        val samples = FloatArray(8_192) { 0.25f }
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(buffer::putFloat)
        val bytes = buffer.array()

        equalizer.processInterleaved(bytes, 0, bytes.size, AudioFormat.ENCODING_PCM_FLOAT)

        val output = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        output.position(bytes.size - 4)
        assertTrue(equalizer.isProcessingRequired())
        assertEquals(0.5f, output.float, 0.01f)
    }

    @Test
    fun replayGainPositiveGainIsCaughtByLinkedLimiter() {
        val equalizer = SoftwareEqualizer()
        equalizer.configure(44_100, 1)
        equalizer.setReplayGain(enabled = true, factor = 2f)
        val samples = FloatArray(8_192) { 0.75f }
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(buffer::putFloat)
        val bytes = buffer.array()

        equalizer.processInterleaved(bytes, 0, bytes.size, AudioFormat.ENCODING_PCM_FLOAT)

        val output = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0f
        while (output.hasRemaining()) peak = maxOf(peak, kotlin.math.abs(output.float))
        assertTrue(peak <= 0.98f)
        assertTrue(peak > 0.9f)
    }

    @Test
    fun replayGainUnityBypassResetsSmoothingOriginForTheNextTrack() {
        val equalizer = SoftwareEqualizer()
        equalizer.configure(44_100, 1)
        equalizer.setReplayGain(enabled = true, factor = 2f)
        val settle = ByteBuffer.allocate(8_192 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(8_192) { putFloat(0.25f) }
        }.array()
        equalizer.processInterleaved(settle, 0, settle.size, AudioFormat.ENCODING_PCM_FLOAT)

        equalizer.setReplayGain(enabled = true, factor = 1f)
        val unity = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.25f).array()
        equalizer.processInterleaved(unity, 0, unity.size, AudioFormat.ENCODING_PCM_FLOAT)
        assertEquals(0.25f, ByteBuffer.wrap(unity).order(ByteOrder.LITTLE_ENDIAN).float, 0f)

        equalizer.setReplayGain(enabled = true, factor = 1.5f)
        val next = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.25f).array()
        equalizer.processInterleaved(next, 0, next.size, AudioFormat.ENCODING_PCM_FLOAT)
        val firstNextSample = ByteBuffer.wrap(next).order(ByteOrder.LITTLE_ENDIAN).float

        assertTrue(firstNextSample in 0.25f..0.375f)
    }

    @Test
    fun replayGainHostAtUnityLeavesPcmBitsUntouched() {
        val equalizer = SoftwareEqualizer()
        equalizer.configure(44_100, 1)
        equalizer.setReplayGain(enabled = true, factor = 1f)
        val buffer = ByteBuffer.allocate(4 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            floatArrayOf(-1f, -0.25f, 0.25f, 1f).forEach(::putFloat)
        }
        val bytes = buffer.array()
        val original = bytes.copyOf()

        equalizer.processInterleaved(bytes, 0, bytes.size, AudioFormat.ENCODING_PCM_FLOAT)

        assertTrue(equalizer.isProcessingRequired())
        assertArrayEquals(original, bytes)
    }

    @Test
    fun channelBalanceTowardRightOnlyAttenuatesLeftChannel() {
        val equalizer = SoftwareEqualizer()
        equalizer.configure(44_100, 2)
        equalizer.setChannelBalancePercent(100)
        val frames = 8_192
        val buffer = ByteBuffer.allocate(frames * 2 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) {
                putFloat(0.25f)
                putFloat(0.25f)
            }
        }
        val bytes = buffer.array()

        equalizer.processInterleaved(bytes, 0, bytes.size, AudioFormat.ENCODING_PCM_FLOAT)

        val output = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        output.position(bytes.size - 8)
        val left = output.float
        val right = output.float
        assertTrue(equalizer.isProcessingRequired())
        assertEquals(0f, left, 0.01f)
        assertEquals(0.25f, right, 0.001f)
    }

    @Test
    fun channelBalanceTowardLeftScalesOnlyRightChannel() {
        val equalizer = SoftwareEqualizer()
        equalizer.configure(44_100, 2)
        equalizer.setChannelBalancePercent(-50)
        val frames = 8_192
        val buffer = ByteBuffer.allocate(frames * 2 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) {
                putFloat(0.25f)
                putFloat(0.25f)
            }
        }
        val bytes = buffer.array()

        equalizer.processInterleaved(bytes, 0, bytes.size, AudioFormat.ENCODING_PCM_FLOAT)

        val output = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        output.position(bytes.size - 8)
        val left = output.float
        val right = output.float
        assertEquals(0.25f, left, 0.001f)
        assertEquals(0.125f, right, 0.01f)
    }

    @Test
    fun centeredChannelBalanceIsTransparentAndMonoIsUnaffected() {
        val centered = SoftwareEqualizer()
        centered.configure(44_100, 2)
        centered.setChannelBalancePercent(0)
        val stereo = ByteBuffer.allocate(4 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            floatArrayOf(-1f, 0.25f, -0.25f, 1f).forEach(::putFloat)
        }.array()
        val originalStereo = stereo.copyOf()
        centered.processInterleaved(stereo, 0, stereo.size, AudioFormat.ENCODING_PCM_FLOAT)
        assertArrayEquals(originalStereo, stereo)

        val mono = SoftwareEqualizer()
        mono.configure(44_100, 1)
        mono.setChannelBalancePercent(100)
        assertFalse(mono.isProcessingRequired())
        val monoBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.25f).array()
        mono.processInterleaved(monoBytes, 0, monoBytes.size, AudioFormat.ENCODING_PCM_FLOAT)
        assertEquals(0.25f, ByteBuffer.wrap(monoBytes).order(ByteOrder.LITTLE_ENDIAN).float, 0f)

        val monoPcm16 = byteArrayOf(0x34, 0x12)
        val originalMonoPcm16 = monoPcm16.copyOf()
        mono.processInterleaved(monoPcm16, 0, monoPcm16.size, AudioFormat.ENCODING_PCM_16BIT)
        assertArrayEquals(originalMonoPcm16, monoPcm16)
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
