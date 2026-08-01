package com.mica.music.media

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import com.mica.music.media.ape.ApeExtractor
import com.mica.music.media.dsf.DsfExtractor

/**
 * Registers Mica-specific progressive extractors ahead of Media3 defaults.
 */
@UnstableApi
class MicaExtractorsFactory private constructor(
    private val defaults: DefaultExtractorsFactory,
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> =
        createExtractors(Uri.EMPTY, emptyMap())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> {
        val extractors = ArrayList<Extractor>(2 + DEFAULT_EXTRACTOR_COUNT)
        // Always register; DsfExtractor.sniff() matches the DSD file header only.
        extractors.add(DsfExtractor())
        extractors.add(ApeExtractor())
        extractors.addAll(defaults.createExtractors(uri, responseHeaders).toList())
        return extractors.toTypedArray()
    }

    companion object {
        private const val DEFAULT_EXTRACTOR_COUNT = 20

        fun create(): ExtractorsFactory = MicaExtractorsFactory(DefaultExtractorsFactory())
    }
}
