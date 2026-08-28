package com.mica.music.media.loudness

import android.net.Uri
import androidx.media3.decoder.ffmpeg.OfflineFfmpegPcmDecoder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.media.ape.ApeExtractor
import com.mica.music.media.dsf.DsfExtractor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineLoudnessDecodeContractTest {
    @Test
    fun generatedAlacDecodesThroughFfmpegForOfflineLoudnessScan() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val file = File(context.cacheDir, "loudness-contract-silence-alac.m4a")
        assetContext.assets.open("media/contract-silence-alac.m4a").use { input ->
            file.outputStream().use(input::copyTo)
        }

        var callbackSampleRate = 0
        var callbackChannels = 0
        var deliveredSamples = 0L
        val result = OfflineFfmpegPcmDecoder.decode(
            context,
            Uri.fromFile(file),
            object : OfflineFfmpegPcmDecoder.PcmConsumer {
                override fun onFormat(sampleRateHz: Int, channelCount: Int) {
                    callbackSampleRate = sampleRateHz
                    callbackChannels = channelCount
                }

                override fun onPcm(interleaved: FloatArray, sampleCount: Int): Boolean {
                    deliveredSamples += sampleCount
                    return true
                }
            },
        )

        assertTrue(result.decoderName.contains("ffmpeg", ignoreCase = true))
        assertEquals(44_100, result.sampleRateHz)
        assertEquals(2, result.channelCount)
        assertEquals(result.sampleRateHz, callbackSampleRate)
        assertEquals(result.channelCount, callbackChannels)
        assertTrue(result.sampleCount > 0L)
        assertEquals(result.sampleCount, deliveredSamples)
    }

    // Fixture provenance: third_party/taglib/src/main/cpp/taglib/tests/data/mac-399.ape.
    @Test
    fun apeUsesMicaExtractorThenFfmpegAndProducesR128Result() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = copyAssetToCache("media/contract-ape.ape", "loudness-contract.ape")
        val consumer = LoudnessScanManager.LoudnessPcmConsumer(context, dsd = false)

        val result = OfflineMicaExtractorPcmDecoder.decode(
            context,
            Uri.fromFile(file),
            ApeExtractor(),
            consumer,
        )
        val analysis = consumer.finish(file.length(), file.lastModified())

        assertTrue(result.decoderName.contains("ffmpeg", ignoreCase = true))
        assertEquals(44_100, result.sampleRateHz)
        assertEquals(2, result.channelCount)
        assertTrue(result.sampleCount > 0L)
        assertNotNull(analysis)
        assertTrue(analysis!!.isValid)
    }

    @Test
    fun dsfUsesMicaExtractorFfmpegAndPlaybackDecimationBeforeR128() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Mirrors a real MediaStore entry observed on device: a valid DSF whose final suffix is .dsd.
        val file = File(context.cacheDir, "loudness-contract-dsd64.dsf.dsd")
        file.writeBytes(buildSyntheticDsd64(durationSeconds = 1))
        val consumer = LoudnessScanManager.LoudnessPcmConsumer(context, dsd = true)

        assertTrue(LoudnessScanManager.hasDsfHeader(context, Uri.fromFile(file)))

        val result = OfflineMicaExtractorPcmDecoder.decode(
            context,
            Uri.fromFile(file),
            DsfExtractor(),
            consumer,
        )
        val analysis = consumer.finish(file.length(), file.lastModified())

        assertTrue(result.decoderName.contains("ffmpeg", ignoreCase = true))
        assertEquals(352_800, result.sampleRateHz)
        assertEquals(2, result.channelCount)
        assertTrue(result.sampleCount > 0L)
        assertNotNull(analysis)
        assertTrue(analysis!!.isValid)
    }

    private fun copyAssetToCache(asset: String, name: String): File {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        return File(context.cacheDir, name).also { file ->
            assetContext.assets.open(asset).use { input ->
                file.outputStream().use(input::copyTo)
            }
        }
    }

    /** Small deterministic DSF64 stream; payload noise is intentional so R128 crosses its gate. */
    private fun buildSyntheticDsd64(durationSeconds: Int): ByteArray {
        val channelCount = 2
        val sampleRate = 2_822_400
        val sampleCount = sampleRate.toLong() * durationSeconds
        val blockSizePerChannel = 4096
        val bytesPerChannel = (sampleCount + 7L) / 8L
        val blocks = (bytesPerChannel + blockSizePerChannel - 1L) / blockSizePerChannel
        val payloadBytes = (blocks * blockSizePerChannel * channelCount).toInt()
        val dataChunkSize = 12L + payloadBytes
        val totalFileSize = 28L + 52L + dataChunkSize
        val buffer = ByteBuffer.allocate(totalFileSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("DSD ".toByteArray(StandardCharsets.US_ASCII))
        buffer.putLong(28L)
        buffer.putLong(totalFileSize)
        buffer.putLong(0L)

        buffer.put("fmt ".toByteArray(StandardCharsets.US_ASCII))
        buffer.putLong(52L)
        buffer.putInt(1)
        buffer.putInt(0)
        buffer.putInt(2)
        buffer.putInt(channelCount)
        buffer.putInt(sampleRate)
        buffer.putInt(1)
        buffer.putLong(sampleCount)
        buffer.putInt(blockSizePerChannel)
        buffer.putInt(0)

        buffer.put("data".toByteArray(StandardCharsets.US_ASCII))
        buffer.putLong(dataChunkSize)
        var state = 0x13579BDF
        repeat(payloadBytes) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            buffer.put((state ushr 24).toByte())
        }
        return buffer.array()
    }
}
