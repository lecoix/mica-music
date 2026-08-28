package com.mica.music.media.loudness

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class R128LoudnessAnalyzerTest {
    @Test
    fun halvingAmplitudeLowersIntegratedLoudnessByAboutSixDb() {
        val loud = analyzeSine(amplitude = 0.5f)
        val quiet = analyzeSine(amplitude = 0.25f)
        val delta = loud.integratedLufs!! - quiet.integratedLufs!!

        assertEquals(6.02, delta.toDouble(), 0.20)
        assertEquals(0.5, loud.samplePeak!!.toDouble(), 0.002)
        assertEquals(0.25, quiet.samplePeak!!.toDouble(), 0.002)
    }

    @Test
    fun silenceProducesNoValidIntegratedResult() {
        val analyzer = R128LoudnessAnalyzer(SAMPLE_RATE, 2)
        analyzer.addInterleaved(FloatArray(SAMPLE_RATE * 2))
        assertTrue(!analyzer.finish(1L, 1L).isValid)
    }

    private fun analyzeSine(amplitude: Float): com.mica.music.data.LoudnessAnalysis {
        val analyzer = R128LoudnessAnalyzer(SAMPLE_RATE, 2)
        val chunkFrames = 4096
        val chunk = FloatArray(chunkFrames * 2)
        var frame = 0
        val totalFrames = SAMPLE_RATE * 5
        while (frame < totalFrames) {
            val frames = minOf(chunkFrames, totalFrames - frame)
            for (index in 0 until frames) {
                val sample = amplitude * sin(2.0 * PI * 1_000.0 * (frame + index) / SAMPLE_RATE).toFloat()
                chunk[index * 2] = sample
                chunk[index * 2 + 1] = sample
            }
            analyzer.addInterleaved(chunk, frames * 2)
            frame += frames
        }
        return analyzer.finish(sourceSizeBytes = 123L, sourceModifiedMs = 456L)
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
    }
}
