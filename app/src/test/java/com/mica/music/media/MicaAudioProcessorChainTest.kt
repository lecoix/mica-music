package com.mica.music.media

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MicaAudioProcessorChainTest {

    @Test
    fun exposesOnlyConfiguredProcessors() {
        val dsd = DsdDecimationAudioProcessor(RuntimeEnvironment.getApplication())
        val eq = MicaEqualizerManager.audioProcessor
        val chain = MicaAudioProcessorChain(dsd, eq)

        assertArrayEquals(arrayOf(dsd, eq), chain.audioProcessors)
    }

    @Test
    fun skipsSilenceAndSonicSideEffects() {
        val chain = MicaAudioProcessorChain()
        val parameters = PlaybackParameters(1.25f)

        assertEquals(parameters, chain.applyPlaybackParameters(parameters))
        assertEquals(true, chain.applySkipSilenceEnabled(true))
        assertEquals(5_000L, chain.getMediaDuration(5_000L))
        assertEquals(0L, chain.getSkippedOutputFrameCount())
    }
}
