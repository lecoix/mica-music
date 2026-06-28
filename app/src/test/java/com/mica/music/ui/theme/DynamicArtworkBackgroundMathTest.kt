package com.mica.music.ui.theme

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicArtworkBackgroundMathTest {

    @Test
    fun scaleFactors_matchReverseEngineeringBaseline() {
        assertEquals(16f, DynamicArtworkBackgroundMath.normalScaleFactor(420), 0f)
        assertEquals(24f, DynamicArtworkBackgroundMath.normalScaleFactor(419), 0f)
        assertEquals(48f, DynamicArtworkBackgroundMath.reducedScaleFactor(420), 0f)
        assertEquals(72f, DynamicArtworkBackgroundMath.reducedScaleFactor(419), 0f)
    }

    @Test
    fun targetSize_usesOverscannedLowResolutionTexture() {
        val size = DynamicArtworkBackgroundMath.targetSize(
            viewWidth = 1080,
            viewHeight = 2400,
            scaleFactor = 16f,
        )

        assertEquals(DynamicArtworkTargetSize(width = 88, height = 195), size)
    }

    @Test
    fun generatedMesh_hasExpectedVertexCountAndBounds() {
        val mesh = DynamicArtworkMesh.generate(seed = 42L, strength = 0.08f)

        assertEquals(DynamicArtworkMesh.VertexCount, mesh.size)
        assertTrue(mesh.all { it in 0f..1f })
    }

    @Test
    fun generatedMesh_isDeterministicButSeeded() {
        val first = DynamicArtworkMesh.generate(seed = 42L, strength = 0.08f)
        val second = DynamicArtworkMesh.generate(seed = 42L, strength = 0.08f)
        val other = DynamicArtworkMesh.generate(seed = 43L, strength = 0.08f)

        assertArrayEquals(first, second, 0f)
        assertFalse(first.contentEquals(other))
    }
}
