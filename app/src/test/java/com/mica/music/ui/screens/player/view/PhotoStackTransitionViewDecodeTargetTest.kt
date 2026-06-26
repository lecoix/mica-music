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

    @Suppress("UNCHECKED_CAST")
    private fun <T> PhotoStackTransitionView.getPrivate(name: String): T =
        javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(this@getPrivate) as T
        }
}
