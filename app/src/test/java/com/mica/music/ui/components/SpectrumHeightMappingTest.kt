package com.mica.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumHeightMappingTest {
    @Test
    fun matchesMainBranchSquareRootHeightCurve() {
        val valley = spectrumHeightFraction(0.02f)
        val low = spectrumHeightFraction(0.10f)
        val middle = spectrumHeightFraction(0.50f)
        val peak = spectrumHeightFraction(1f)

        assertEquals(0.1414f, valley, 0.0001f)
        assertEquals(0.3162f, low, 0.0001f)
        assertEquals(0.7071f, middle, 0.0001f)
        assertEquals(1f, peak, 0.0001f)
        assertTrue(valley < low && low < middle && middle < peak)
    }
}
