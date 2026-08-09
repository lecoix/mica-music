package com.mica.music.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeCoverFlowCoverPoseTest {
    @Test
    fun immersivePoseMakesParallelCoverFullHeightAndCentersCoverItself() {
        val pose = landscapeCoverFlowImmersivePose(
            stageHeight = 400.dp,
            coverHeight = 280.dp,
            referenceCenterScale = 0.76f,
        )
        val transformedCoverCenter = 280f * (0.45f + 0.05f * pose.scale)
        val visibleCoverCenter = transformedCoverCenter + pose.translationY.value
        val parallelVisibleHeight = 280f * 0.76f * pose.scale

        assertEquals(400f, parallelVisibleHeight, 0.01f)
        assertEquals(200f, visibleCoverCenter, 0.01f)
    }

    @Test
    fun immersivePoseGivesParallelAndRetroTheSameOuterScaleNumber() {
        val parallel = landscapeCoverFlowImmersivePose(
            stageHeight = 400.dp,
            coverHeight = 280.dp,
            referenceCenterScale = 0.76f,
        )
        val retro = landscapeCoverFlowImmersivePose(
            stageHeight = 400.dp,
            coverHeight = 280.dp,
            referenceCenterScale = 0.76f,
        )

        assertEquals(parallel.scale, retro.scale, 0.001f)
    }

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
