package com.mica.music.ui.theme

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.mica.music.data.CustomWallpaperCrop
import com.mica.music.util.customWallpaperBarSliceFallbackReason
import com.mica.music.util.effectiveWallpaperBarSliceAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnimatedThemeWallpaperTest {

    private fun testViewportFrame(): ImageBitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()

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
    fun barSliceReadyUsesSharedViewportFrameWithoutLocalImageLoad() {
        val ready = customWallpaperBarSliceReady(
            hasWallpaperFile = true,
            wallpaperFailed = false,
            viewportFrame = testViewportFrame(),
            sliceTopPx = 700f,
            sliceHeightPx = 80f,
            viewportTopPx = 20f,
            viewportHeightPx = 780f,
        )
        assertTrue(ready)
    }

    @Test
    fun barSliceReadyRejectsNaNAnchorEvenWhenViewportFrameExists() {
        val ready = customWallpaperBarSliceReady(
            hasWallpaperFile = true,
            wallpaperFailed = false,
            viewportFrame = testViewportFrame(),
            sliceTopPx = Float.NaN,
            sliceHeightPx = 80f,
            viewportTopPx = 20f,
            viewportHeightPx = 780f,
        )
        assertFalse(ready)
    }

    @Test
    fun wallpaperViewportStateRetainsLastBarSliceAnchor() {
        val viewport = WallpaperViewportState()
        viewport.updateBarSliceAnchor(720f, 96f)
        assertEquals(720f, viewport.barSliceTopPx, 0.001f)
        assertEquals(96f, viewport.barSliceHeightPx, 0.001f)
        viewport.updateBarSliceAnchor(Float.NaN, 96f)
        assertEquals(720f, viewport.barSliceTopPx, 0.001f)
    }

    @Test
    fun barSliceFallbackReasonIdentifiesMissingViewportFrame() {
        assertEquals(
            "viewport-frame-null",
            customWallpaperBarSliceFallbackReason(
                hasWallpaperFile = true,
                viewportFrame = false,
                sliceTopPx = 700f,
                sliceHeightPx = 80f,
                viewportTopPx = 20f,
                viewportHeightPx = 780f,
            ),
        )
    }

    @Test
    fun effectiveAnchorFallsBackToCachedWhenLiveLayoutIsTransientTopZero() {
        val (top, height) = effectiveWallpaperBarSliceAnchor(
            liveTopPx = 0f,
            liveHeightPx = 238f,
            cachedTopPx = 2534f,
            cachedHeightPx = 238f,
            viewportTopPx = 0f,
            viewportHeightPx = 2772f,
        )
        assertEquals(2534f, top, 0.001f)
        assertEquals(238f, height, 0.001f)
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
