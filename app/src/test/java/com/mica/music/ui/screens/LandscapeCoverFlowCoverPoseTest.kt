package com.mica.music.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeCoverFlowCoverPoseTest {
    @Test
    fun lyricsLaneKeepsFullAlphaAndMovesTowardLeftLane() {
        val pose = landscapeCoverFlowCoverPose(
            progress = 1f,
            exit = LandscapeCoverFlowCoverExit.LyricsLane,
            playerCoverScale = 1.2f,
            playerCoverTranslationY = 100.dp,
            lyricsCoverScale = 0.5f,
            lyricsCoverTranslationY = 20.dp,
            coverTranslationXTarget = (-200).dp,
        )
        assertEquals(0.5f, pose.scale, 0.001f)
        assertEquals((-200).dp, pose.translationX)
        assertEquals(20.dp, pose.translationY)
        assertEquals(1f, pose.alpha, 0.001f)
        assertEquals(0f, pose.rotationX, 0.001f)
    }

    @Test
    fun cloudFoldShrinksInPlaceWithoutLaneShift() {
        val pose = landscapeCoverFlowCoverPose(
            progress = 1f,
            exit = LandscapeCoverFlowCoverExit.CloudFold,
            playerCoverScale = 1.2f,
            playerCoverTranslationY = 100.dp,
            lyricsCoverScale = 0.5f,
            lyricsCoverTranslationY = 20.dp,
            coverTranslationXTarget = (-200).dp,
        )
        assertEquals(0.dp, pose.translationX)
        assertTrue(pose.scale < 1.2f)
        assertEquals(0f, pose.alpha, 0.001f)
        assertEquals(0f, pose.rotationX, 0.001f)
    }

    @Test
    fun cloudScatterLiftsWithoutTilt() {
        val pose = landscapeCoverFlowCoverPose(
            progress = 1f,
            exit = LandscapeCoverFlowCoverExit.CloudScatter,
            playerCoverScale = 1.2f,
            playerCoverTranslationY = 100.dp,
            lyricsCoverScale = 0.5f,
            lyricsCoverTranslationY = 20.dp,
            coverTranslationXTarget = (-200).dp,
        )
        assertEquals(0.dp, pose.translationX)
        assertTrue(pose.translationY < 100.dp)
        assertEquals(0f, pose.alpha, 0.001f)
        assertEquals(0f, pose.rotationX, 0.001f)
    }
}
