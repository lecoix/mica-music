package com.mica.music.media.usbprototype

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.test.core.app.ApplicationProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExactPcm24PrototypePackingTest {
    @Test
    fun floatIsDirectAtMedia3BoundarySoFfmpegDoesNotFallBackToPcm16() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = UsbSk02AudioOutputProvider(context)
        val config = AudioOutputProvider.FormatConfig.Builder(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setSampleRate(96_000)
                .setChannelCount(2)
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .build(),
        ).build()

        assertEquals(
            AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY,
            provider.getFormatSupport(config).supportLevel,
        )
    }

    @Test
    fun exactSignedPcm32FloatsPackWithoutChangingInputPosition() {
        val input = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
            .putFloat(-1f)
            .putFloat(8_388_607f / 8_388_608f)
            .putFloat(1f / 8_388_608f)
            .putFloat(-1f / 8_388_608f)
            .flip() as ByteBuffer
        val output = ByteBuffer.allocate(16)

        ExactPcm32PrototypePacking.pack(input, frames = 2, output = output)

        assertEquals(0, input.position())
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x80.toByte(),
                0x00, 0xff.toByte(), 0xff.toByte(), 0x7f,
                0x00, 0x01, 0x00, 0x00,
                0x00, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            ),
            output.array(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonExactFloatFailsClosed() {
        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
            .putFloat(1f / 4_294_967_296f)
            .putFloat(0f)
            .flip() as ByteBuffer

        ExactPcm32PrototypePacking.pack(input, frames = 1, output = ByteBuffer.allocate(8))
    }
}
