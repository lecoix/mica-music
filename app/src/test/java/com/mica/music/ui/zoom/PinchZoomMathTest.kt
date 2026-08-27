package com.mica.music.ui.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchZoomMathTest {
    @Test
    fun `one physical gesture is clamped to one adjacent preset pair`() {
        val hugeExpansion = PinchZoomMath.lockedPositionForDistance(
            sourceIndex = 1,
            targetIndex = 2,
            baselineDistancePx = 100f,
            distancePx = 100f * PinchZoomMath.DefaultDistanceRatioPerPreset *
                PinchZoomMath.DefaultDistanceRatioPerPreset * PinchZoomMath.DefaultDistanceRatioPerPreset,
        )
        assertEquals(2f, hugeExpansion, 0f)

        val hugeContraction = PinchZoomMath.lockedPositionForDistance(
            sourceIndex = 2,
            targetIndex = 1,
            baselineDistancePx = 100f,
            distancePx = 1f,
        )
        assertEquals(1f, hugeContraction, 0f)
    }

    @Test
    fun `position clamps at both ends`() {
        assertEquals(
            0f,
            PinchZoomMath.positionForDistance(0f, 100f, 1f, presetCount = 4),
            0f,
        )
        assertEquals(
            3f,
            PinchZoomMath.positionForDistance(3f, 100f, 1000f, presetCount = 4),
            0f,
        )
    }

    @Test
    fun `segment reports adjacent presets and fractional progress`() {
        val segment = PinchZoomMath.segment(1.35f, presetCount = 4)
        assertEquals(1, segment.lowerIndex)
        assertEquals(2, segment.upperIndex)
        assertEquals(0.35f, segment.progress, 0.0001f)
    }

    @Test
    fun `strong radial velocity wins settle direction`() {
        assertEquals(
            2,
            PinchZoomMath.settleTarget(1.1f, 650f, 1, 1, presetCount = 4),
        )
        assertEquals(
            1,
            PinchZoomMath.settleTarget(1.9f, -650f, -1, -1, presetCount = 4),
        )
    }

    @Test
    fun `normal progress commits after point three`() {
        assertEquals(
            2,
            PinchZoomMath.settleTarget(1.31f, 0f, 1, 1, presetCount = 4),
        )
        assertEquals(
            1,
            PinchZoomMath.settleTarget(1.19f, 0f, 1, 1, presetCount = 4),
        )
    }

    @Test
    fun `reversal uses high hysteresis before committing opposite direction`() {
        val stayAtSource = PinchZoomMath.settleTarget(
            position = 1.35f,
            radialVelocityDpPerSecond = 0f,
            primaryDirection = 1,
            lastDirection = -1,
            presetCount = 4,
        )
        val commitReverse = PinchZoomMath.settleTarget(
            position = 1.15f,
            radialVelocityDpPerSecond = 0f,
            primaryDirection = 1,
            lastDirection = -1,
            presetCount = 4,
        )
        assertEquals(2, stayAtSource)
        assertEquals(1, commitReverse)
    }

    @Test
    fun `paired source and target items meet at the same interpolated rect`() {
        val lower = PinchZoomItemRect(
            leftPx = 0f,
            topPx = 20f,
            widthPx = 100f,
            heightPx = 60f,
        )
        val upper = PinchZoomItemRect(
            leftPx = 160f,
            topPx = 220f,
            widthPx = 220f,
            heightPx = 140f,
        )
        val source = calculatePinchZoomItemMorph(
            current = lower,
            counterpart = upper,
            progress = 0.5f,
            fromLower = true,
            transitionActive = true,
        )
        val target = calculatePinchZoomItemMorph(
            current = upper,
            counterpart = lower,
            progress = 0.5f,
            fromLower = false,
            transitionActive = true,
        )

        val sourceLeft = lower.leftPx + source.translationXPx
        val sourceTop = lower.topPx + source.translationYPx
        val sourceWidth = source.contentWidthPx ?: error("source width missing")
        val sourceHeight = source.contentHeightPx ?: error("source height missing")
        val targetLeft = upper.leftPx + target.translationXPx
        val targetTop = upper.topPx + target.translationYPx
        val targetWidth = target.contentWidthPx ?: error("target width missing")
        val targetHeight = target.contentHeightPx ?: error("target height missing")

        assertEquals(sourceLeft, targetLeft, 0.001f)
        assertEquals(sourceTop, targetTop, 0.001f)
        assertEquals(sourceWidth, targetWidth, 0.001f)
        assertEquals(sourceHeight, targetHeight, 0.001f)
        assertEquals(80f, sourceLeft, 0.001f)
        assertEquals(120f, sourceTop, 0.001f)
        assertEquals(160f, sourceWidth, 0.001f)
        assertEquals(100f, sourceHeight, 0.001f)
    }

    @Test
    fun `bounds morph keeps lazy slot identity while remeasuring child`() {
        val lower = PinchZoomItemRect(0f, 0f, 320f, 72f)
        val upper = PinchZoomItemRect(24f, 120f, 128f, 180f)
        val morph = calculatePinchZoomItemMorph(
            current = lower,
            counterpart = upper,
            progress = 0.5f,
            fromLower = true,
            transitionActive = true,
        )
        assertEquals(320f, morph.baseWidthPx ?: error("base width missing"), 0f)
        assertEquals(72f, morph.baseHeightPx ?: error("base height missing"), 0f)
        assertEquals(224f, morph.contentWidthPx ?: error("content width missing"), 0.001f)
        assertEquals(126f, morph.contentHeightPx ?: error("content height missing"), 0.001f)
    }

    @Test
    fun `item style crossfade is concentrated around middle of morph`() {
        assertEquals(0f, PinchZoomMath.contentCrossfadeProgress(0.30f), 0f)
        assertEquals(0.5f, PinchZoomMath.contentCrossfadeProgress(0.50f), 0.0001f)
        assertEquals(1f, PinchZoomMath.contentCrossfadeProgress(0.70f), 0f)
    }

    @Test
    fun `unmatched visible item stays geometrically stable while fading`() {
        val current = PinchZoomItemRect(10f, 30f, 100f, 80f)
        val morph = calculatePinchZoomItemMorph(
            current = current,
            counterpart = null,
            progress = 0.5f,
            fromLower = true,
            transitionActive = true,
        )
        assertEquals(0f, morph.translationXPx, 0f)
        assertEquals(0f, morph.translationYPx, 0f)
        assertEquals(null, morph.contentWidthPx)
        assertEquals(null, morph.contentHeightPx)
        assertEquals(0.5f, morph.alpha, 0.0001f)
    }

    @Test
    fun `reversal cannot cross through the locked source into the opposite preset`() {
        val source = 2
        val target = 3
        val reversedPastBaseline = PinchZoomMath.lockedPositionForDistance(
            sourceIndex = source,
            targetIndex = target,
            baselineDistancePx = 100f,
            distancePx = 10f,
        )
        assertEquals(source.toFloat(), reversedPastBaseline, 0f)
        assertTrue(reversedPastBaseline in source.toFloat()..target.toFloat())
    }

}
