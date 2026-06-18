package com.mica.music.media

import android.net.Uri
import androidx.media3.extractor.Extractor
import com.mica.music.media.dsf.DsfExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MicaExtractorsFactoryTest {

    @Test
    fun alwaysRegistersDsfExtractorFirst() {
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

    private fun assertDsfFirst(extractors: Array<Extractor>) {
        assertTrue(extractors.isNotEmpty())
        assertEquals(DsfExtractor::class.java, extractors[0].javaClass)
    }
}
