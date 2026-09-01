package com.mica.music.media

import android.net.Uri
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.amr.AmrExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.ts.AdtsExtractor
import com.mica.music.media.ape.ApeExtractor
import com.mica.music.media.dsf.DsfExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MicaExtractorsFactoryTest {

    @Test
    fun alwaysRegistersDsfThenApeExtractors() {
        val factory = MicaExtractorsFactory.create()
        assertDsfFirst(factory.createExtractors())
        assertDsfFirst(
            factory.createExtractors(
                Uri.parse("content://com.android.externalstorage.documents/document/primary"),
                emptyMap(),
            ),
        )
        assertDsfFirst(
            factory.createExtractors(
                Uri.parse("file:///music/track.flac"),
                emptyMap(),
            ),
        )
    }

    @Test
    fun enablesConstantBitrateSeekingForAdtsAac() {
        val adts = MicaExtractorsFactory.create().createExtractors()
            .first { it is AdtsExtractor } as AdtsExtractor

        assertTrue(
            extractorFlags(adts) and AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING != 0,
        )
    }

    @Test
    fun doesNotEnableConstantBitrateSeekingForMp3OrAmr() {
        val extractors = MicaExtractorsFactory.create().createExtractors()
        val mp3 = extractors.first { it is Mp3Extractor } as Mp3Extractor
        val amr = extractors.first { it is AmrExtractor } as AmrExtractor

        assertEquals(0, extractorFlags(mp3) and Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
        assertEquals(0, extractorFlags(amr) and AmrExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
    }

    private fun extractorFlags(extractor: Extractor): Int {
        val flagsField = extractor.javaClass.getDeclaredField("flags").apply { isAccessible = true }
        return flagsField.getInt(extractor)
    }

    private fun assertDsfFirst(extractors: Array<Extractor>) {
        assertTrue(extractors.size >= 2)
        assertEquals(DsfExtractor::class.java, extractors[0].javaClass)
        assertEquals(ApeExtractor::class.java, extractors[1].javaClass)
    }
}
