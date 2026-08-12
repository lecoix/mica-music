package com.mica.music.ui.screens.player.view

import androidx.test.core.app.ApplicationProvider
import com.mica.music.imaging.CoverDecodeTarget
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhotoStackTransitionViewDecodeTargetTest {

    @Test
    fun setFrameUpdatesDecodeTargetToArtworkSize() {
        val view = PhotoStackTransitionView(ApplicationProvider.getApplicationContext())
        val frame = PhotoStackTransitionFramePx(
            slotWidthPx = 320f,
            slotHeightPx = 400f,
            cardTopInsetPx = 0f,
            cardWidthPx = 260f,
            cardHeightPx = 320f,
            artworkInsetTopPx = 18f,
            artworkInsetHorizontalPx = 16f,
            waveformHeightPx = 24f,
        )

        view.setFrame(frame)

        val expectedArtworkSize = frame.cardWidthPx - frame.artworkInsetHorizontalPx * 2f
        val expected = CoverDecodeTarget.fromPixels(expectedArtworkSize, expectedArtworkSize)
        val actual = view.getPrivate<CoverDecodeTarget>("decodeTarget")
        assertEquals(expected.widthPx, actual.widthPx)
        assertEquals(expected.heightPx, actual.heightPx)
    }

    @Test
    fun decodeTargetOverrideRemainsStableAcrossFrameResize() {
        val view = PhotoStackTransitionView(ApplicationProvider.getApplicationContext())
        val normalFrame = PhotoStackTransitionFramePx(
            slotWidthPx = 320f,
            slotHeightPx = 400f,
            cardTopInsetPx = 0f,
            cardWidthPx = 260f,
            cardHeightPx = 320f,
            artworkInsetTopPx = 18f,
            artworkInsetHorizontalPx = 16f,
            waveformHeightPx = 24f,
        )
        val immersiveFrame = normalFrame.copy(
            slotWidthPx = 390f,
            slotHeightPx = 520f,
            cardTopInsetPx = 52f,
            cardWidthPx = 340f,
            cardHeightPx = 420f,
            artworkInsetTopPx = 22f,
            artworkInsetHorizontalPx = 20f,
        )
        val stableTarget = CoverDecodeTarget.fromPixels(320f, 320f)

        view.setDecodeTargetOverride(stableTarget)
        view.setFrame(normalFrame)
        assertEquals(stableTarget, view.getPrivate<CoverDecodeTarget>("decodeTarget"))

        view.setFrame(immersiveFrame)
        assertEquals(stableTarget, view.getPrivate<CoverDecodeTarget>("decodeTarget"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> PhotoStackTransitionView.getPrivate(name: String): T =
        javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(this@getPrivate) as T
        }
}
