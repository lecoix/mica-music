package com.mica.music.media

import android.net.Uri
import androidx.media3.extractor.Extractor
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
        val flagsField = AdtsExtractor::class.java.getDeclaredField("flags").apply { isAccessible = true }
        val flags = flagsField.getInt(adts)

        assertTrue(flags and AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING != 0)
    }

    private fun assertDsfFirst(extractors: Array<Extractor>) {
        assertTrue(extractors.size >= 2)
        assertEquals(DsfExtractor::class.java, extractors[0].javaClass)
        assertEquals(ApeExtractor::class.java, extractors[1].javaClass)
    }
}
