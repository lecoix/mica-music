package com.mica.music.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhotoStackLyricsTransitionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun leftDragOpensAndRightDragClosesByTheSameProgressRule() {
        assertEquals(
            0.25f,
            photoStackLyricsProgressAfterDrag(
                currentProgress = 0f,
                deltaX = -100f,
                widthPx = 400f,
            ),
            0.0001f,
        )
        assertEquals(
            0.75f,
            photoStackLyricsProgressAfterDrag(
                currentProgress = 1f,
                deltaX = 100f,
                widthPx = 400f,
            ),
            0.0001f,
        )
    }

    @Test
    fun progressIsClampedToThePageBounds() {
        assertEquals(0f, photoStackLyricsProgressAfterDrag(0f, 100f, 400f), 0.0001f)
        assertEquals(1f, photoStackLyricsProgressAfterDrag(1f, -100f, 400f), 0.0001f)
    }

    @Test
    fun targetCommitsAtTheConfiguredQuarterPageThreshold() {
        assertEquals(0f, photoStackLyricsTargetProgress(0.2499f, startedOpen = false), 0.0001f)
        assertEquals(1f, photoStackLyricsTargetProgress(0.25f, startedOpen = false), 0.0001f)
        assertEquals(1f, photoStackLyricsTargetProgress(0.7501f, startedOpen = true), 0.0001f)
        assertEquals(0f, photoStackLyricsTargetProgress(0.75f, startedOpen = true), 0.0001f)
    }

    @Test
    fun transitionFrameKeepsBothPagesMountedAtTheMiddle() {
        val frame = photoStackLyricsTransitionFrame(
            progress = 0.5f,
            targetOpen = true,
            dragging = false,
        )

        assertTrue(frame.playbackMounted)
        assertTrue(frame.lyricsMounted)
        assertEquals(-0.5f, frame.playbackTranslationFraction, 0.0001f)
        assertEquals(0.5f, frame.lyricsTranslationFraction, 0.0001f)
        assertEquals(0.5f, frame.playbackAlpha, 0.0001f)
        assertEquals(0.5f, frame.lyricsAlpha, 0.0001f)
        assertEquals(false, frame.playbackInputEnabled)
        assertEquals(false, frame.lyricsInputEnabled)
    }

    @Test
    fun transitionFrameUnmountsOnlyTheInvisibleSettledPage() {
        val playback = photoStackLyricsTransitionFrame(
            progress = 0f,
            targetOpen = false,
            dragging = false,
        )
        val lyrics = photoStackLyricsTransitionFrame(
            progress = 1f,
            targetOpen = true,
            dragging = false,
        )

        assertTrue(playback.playbackMounted)
        assertEquals(false, playback.lyricsMounted)
        assertTrue(playback.playbackInputEnabled)
        assertEquals(false, playback.lyricsInputEnabled)

        assertEquals(false, lyrics.playbackMounted)
        assertTrue(lyrics.lyricsMounted)
        assertEquals(false, lyrics.playbackInputEnabled)
        assertTrue(lyrics.lyricsInputEnabled)
    }

    @Test
    fun closingRemountsPlaybackOffscreenBeforeAnimatingBack() {
        val frame = photoStackLyricsTransitionFrame(
            progress = 1f,
            targetOpen = false,
            dragging = false,
        )

        assertTrue(frame.playbackMounted)
        assertTrue(frame.lyricsMounted)
        assertEquals(-1f, frame.playbackTranslationFraction, 0.0001f)
        assertEquals(0f, frame.playbackAlpha, 0.0001f)
        assertEquals(false, frame.playbackInputEnabled)
        assertEquals(false, frame.lyricsInputEnabled)
    }

    @Test
    fun draggingDisablesSettledPageChildren() {
        val openingDrag = photoStackLyricsTransitionFrame(
            progress = 0f,
            targetOpen = false,
            dragging = true,
        )
        val closingDrag = photoStackLyricsTransitionFrame(
            progress = 1f,
            targetOpen = true,
            dragging = true,
        )

        assertEquals(false, openingDrag.playbackInputEnabled)
        assertEquals(false, closingDrag.lyricsInputEnabled)
    }

    @Test
    fun disabledTransitionLayerConsumesChildClicks() {
        var clicks = 0
        composeRule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("blocked-photo-stack-layer")
                    .photoStackLyricsInputEnabled(enabled = false)
                    .clickable { clicks += 1 },
            )
        }

        composeRule.onNodeWithTag("blocked-photo-stack-layer").performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(0, clicks) }
    }

    @Test
    fun blankPageSwipeReachesTheSettledCallback() {
        var settledOpen = false
        composeRule.setContent {
            val transition = rememberPhotoStackLyricsTransition(
                enabled = true,
                open = false,
                motionEnabled = false,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("photo-stack-lyrics-swipe")
                    .photoStackLyricsSwipe(
                        enabled = true,
                        transition = transition,
                        onSettled = { settledOpen = it },
                    ),
            )
        }

        composeRule.onNodeWithTag("photo-stack-lyrics-swipe").performTouchInput { swipeLeft() }
        composeRule.runOnIdle { assertTrue(settledOpen) }
    }

    @Test
    fun rightSwipeFromLyricsReachesTheClosedCallback() {
        var settledOpen = true
        composeRule.setContent {
            val transition = rememberPhotoStackLyricsTransition(
                enabled = true,
                open = true,
                motionEnabled = false,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("photo-stack-lyrics-close-swipe")
                    .photoStackLyricsSwipe(
                        enabled = true,
                        transition = transition,
                        onSettled = { settledOpen = it },
                    ),
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("photo-stack-lyrics-close-swipe").performTouchInput {
            swipeRight()
        }
        composeRule.runOnIdle { assertFalse(settledOpen) }
    }
}
