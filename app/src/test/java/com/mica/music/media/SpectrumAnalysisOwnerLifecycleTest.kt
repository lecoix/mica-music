package com.mica.music.media

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumAnalysisOwnerLifecycleTest {
    private val first = Any()
    private val second = Any()

    @Before
    fun setUp() {
        MicaSpectrumAnalyzer.resetAnalysisOwnersForTest()
    }

    @After
    fun tearDown() {
        MicaSpectrumAnalyzer.resetAnalysisOwnersForTest()
        MicaSpectrumAnalyzer.setEnabled(false, notifyPipeline = false)
    }

    @Test
    fun olderActivityStoppingDoesNotDisableNewerStartedActivity() {
        MicaSpectrumAnalyzer.setEnabled(true, notifyPipeline = false)

        MicaSpectrumAnalyzer.setAnalysisActive(first, true)
        MicaSpectrumAnalyzer.setAnalysisActive(second, true)
        MicaSpectrumAnalyzer.setAnalysisActive(first, false)

        assertTrue(MicaSpectrumAnalyzer.isAnalysisActive())

        MicaSpectrumAnalyzer.setAnalysisActive(second, false)
        assertFalse(MicaSpectrumAnalyzer.isAnalysisActive())
    }
}