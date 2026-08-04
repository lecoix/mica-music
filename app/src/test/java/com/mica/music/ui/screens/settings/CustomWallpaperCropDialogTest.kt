package com.mica.music.ui.screens.settings

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.mica.music.data.CustomWallpaperCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomWallpaperCropDialogTest {

    private val imageSize = Size(1000f, 1000f)
    private val viewportSize = Size(500f, 500f)
    private val center = Offset(250f, 250f)

    @Test
    fun pinchZoomKeepsCropCentered() {
        val result = updateWallpaperCropFromGesture(
            crop = CustomWallpaperCrop.Default,
            imageSize = imageSize,
            viewportSize = viewportSize,
            centroid = center,
            pan = Offset.Zero,
            zoom = 2f,
        )

        assertEquals(2f, result.zoom, 0.001f)
        assertEquals(0f, result.offsetX, 0.001f)
        assertEquals(0f, result.offsetY, 0.001f)
    }

    @Test
    fun dragMovesZoomedCropAndClampsAtEdge() {
        val zoomed = CustomWallpaperCrop(zoom = 2f)
        val moved = updateWallpaperCropFromGesture(
            crop = zoomed,
            imageSize = imageSize,
            viewportSize = viewportSize,
            centroid = center,
            pan = Offset(100f, -50f),
            zoom = 1f,
        )
        val clamped = updateWallpaperCropFromGesture(
            crop = moved,
            imageSize = imageSize,
            viewportSize = viewportSize,
            centroid = center,
            pan = Offset(10_000f, 10_000f),
            zoom = 1f,
        )

        assertTrue(moved.offsetX > 0f)
        assertTrue(moved.offsetY < 0f)
        assertEquals(1f, clamped.offsetX, 0.001f)
        assertEquals(1f, clamped.offsetY, 0.001f)
    }
}
