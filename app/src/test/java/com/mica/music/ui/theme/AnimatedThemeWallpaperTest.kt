package com.mica.music.ui.theme

import androidx.compose.ui.geometry.Size
import com.mica.music.data.CustomWallpaperCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnimatedThemeWallpaperTest {

    @Test
    fun unspecifiedImageSizeIsSkippedBeforeReadingDimensions() {
        val geometry = customWallpaperDrawGeometry(
            imageSize = Size.Unspecified,
            viewportSize = Size(400f, 800f),
            crop = CustomWallpaperCrop.Default,
        )

        assertNull(geometry)
    }

    @Test
    fun validImageSizeKeepsCoverScaleAndCentering() {
        val geometry = customWallpaperDrawGeometry(
            imageSize = Size(100f, 200f),
            viewportSize = Size(400f, 400f),
            crop = CustomWallpaperCrop.Default,
        )

        requireNotNull(geometry)
        assertEquals(0f, geometry.left, 0.001f)
        assertEquals(-200f, geometry.top, 0.001f)
        assertEquals(Size(400f, 800f), geometry.size)
    }

    @Test
    fun bottomSliceUsesAFullViewportLayerOffsetToTheViewportOrigin() {
        val geometry = customWallpaperSliceLayerGeometry(
            sliceTopPx = 700f,
            sliceHeightPx = 80f,
            viewportTopPx = 20f,
            viewportHeightPx = 780f,
        )

        requireNotNull(geometry)
        assertEquals(780f, geometry.layerHeightPx, 0.001f)
        assertEquals(-680f, geometry.layerOffsetYPx, 0.001f)
    }

    @Test
    fun barSliceOffsetMatchesViewportAlignedTranslate() {
        assertEquals(-680f, customWallpaperBarSliceOffsetYPx(700f, 20f), 0.001f)
    }

    @Test
    fun stackBlurRadiusMapsDpToTiebaLiteScale() {
        assertEquals(0, customWallpaperStackBlurRadius(0))
        assertEquals(25, customWallpaperStackBlurRadius(8))
        assertEquals(31, customWallpaperStackBlurRadius(10))
        assertEquals(100, customWallpaperStackBlurRadius(32))
    }

    @Test
    fun stackBlurDownsampleScaleCapsLargeViewports() {
        assertEquals(1f, customWallpaperStackBlurDownsampleScale(800, 1200), 0.001f)
        val scale = customWallpaperStackBlurDownsampleScale(1260, 2800)
        assert(scale < 1f)
        assertEquals(
            CustomWallpaperStackBlur.MAX_VIEWPORT_BLUR_PIXELS.toFloat(),
            1260f * 2800f * scale * scale,
            1f,
        )
    }
}
