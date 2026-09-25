package com.mica.music.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RainGlassShaderContractTest {
    @Test
    fun versionDirectiveIsTheFirstShaderLine() {
        assertTrue(RainGlassVertexShader.startsWith("#version 300 es\n"))
        assertTrue(RainGlassFragmentShader.startsWith("#version 300 es\n"))
    }

    @Test
    fun movingDropEnvelopeDoesNotUseReversedSmoothstepEdges() {
        assertFalse(RainGlassFragmentShader.contains("smoothstep(1.0, y, st.y)"))
        assertTrue(RainGlassFragmentShader.contains("1.0 - smoothstep(y, 1.0, st.y)"))
    }
}
