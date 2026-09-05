package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayerLowerLayoutConfigTest {
    @Test
    fun normalizedRepairsOrderAndClampsSpacing() {
        val normalized = PlayerLowerLayoutConfig(
            order = listOf(PlayerLowerComponent.TITLE, PlayerLowerComponent.TITLE),
            hidden = setOf(PlayerLowerComponent.INFO),
            scalePercents = mapOf(PlayerLowerComponent.TITLE to 999),
            spacingDp = 99,
            topPaddingDp = 999,
            bottomPaddingDp = -1,
            lyricsLineCount = 2,
        ).normalized()

        assertEquals(PlayerLowerComponent.entries.size, normalized.order.size)
        assertEquals(PlayerLowerComponent.TITLE, normalized.order.first())
        assertEquals(PlayerLowerLayoutConfig.MAX_SPACING_DP, normalized.spacingDp)
        assertEquals(PlayerLowerLayoutConfig.MAX_SCALE_PERCENT, normalized.scalePercentOf(PlayerLowerComponent.TITLE))
        assertEquals(PlayerLowerLayoutConfig.MAX_BOUNDARY_PADDING_DP, normalized.topPaddingDp)
        assertEquals(PlayerLowerLayoutConfig.MIN_BOUNDARY_PADDING_DP, normalized.bottomPaddingDp)
        assertEquals(PlayerLowerLayoutConfig.THREE_LYRICS_LINE_COUNT, normalized.lyricsLineCount)
        assertFalse(normalized.isVisible(PlayerLowerComponent.INFO))
    }

    @Test
    fun normalizedKeepsSupportedSingleLyricsLineCount() {
        val normalized = PlayerLowerLayoutConfig.Default
            .copy(lyricsLineCount = PlayerLowerLayoutConfig.SINGLE_LYRICS_LINE_COUNT)
            .normalized()

        assertEquals(PlayerLowerLayoutConfig.SINGLE_LYRICS_LINE_COUNT, normalized.lyricsLineCount)
    }

    @Test
    fun moveStopsAtEdgesAndKeepsEveryComponent() {
        val moved = PlayerLowerLayoutConfig.Default
            .move(PlayerLowerComponent.INFO, -1)
            .move(PlayerLowerComponent.CONTROLS, -1)

        assertEquals(PlayerLowerComponent.INFO, moved.order.first())
        assertEquals(PlayerLowerComponent.CONTROLS, moved.order[moved.order.lastIndex - 1])
        assertEquals(PlayerLowerComponent.entries.toSet(), moved.order.toSet())
    }

    @Test
    fun defaultCustomLayoutStartsWithVisibleCover() {
        val config = PlayerLowerLayoutConfig.Default

        assertEquals(PlayerLowerComponent.COVER, config.order.first())
        assertEquals(true, config.isVisible(PlayerLowerComponent.COVER))
        assertEquals(PlayerLowerLayoutConfig.DEFAULT_SCALE_PERCENT, config.scalePercentOf(PlayerLowerComponent.COVER))
    }

    @Test
    fun customThemeUsesHorizontalLyricsAndRejectsImmersiveLower() {
        assertEquals(false, PlayerCoverFlowMode.CUSTOM_STANDARD.supportsImmersiveLower)
        assertEquals(true, PlayerCoverFlowMode.CUSTOM_STANDARD.usesHorizontalLyricsPage)
    }

    @Test
    fun elementOffsetsAreClampedAndZeroOffsetsAreNotStored() {
        val config = PlayerLowerLayoutConfig.Default
            .withElementOffset(PlayerLowerComponent.TITLE, PlayerLowerElementOffset(5_000, -5_000))
            .withElementOffset(PlayerLowerComponent.INFO, PlayerLowerElementOffset.Zero)
            .normalized()

        assertEquals(
            PlayerLowerElementOffset(1_000, -1_000),
            config.offsetOf(PlayerLowerComponent.TITLE),
        )
        assertEquals(PlayerLowerElementOffset.Zero, config.offsetOf(PlayerLowerComponent.INFO))
        assertFalse(config.elementOffsets.containsKey(PlayerLowerComponent.INFO))
    }

    @Test
    fun textAlignDefaultsToCenterAndIsNotStored() {
        val config = PlayerLowerLayoutConfig.Default
            .withTextAlign(PlayerLowerTextTarget.TITLE, PlayerLowerTextAlign.START)
            .withTextAlign(PlayerLowerTextTarget.SUBTITLE, PlayerLowerTextAlign.CENTER)
            .normalized()

        assertEquals(PlayerLowerTextAlign.START, config.textAlignOf(PlayerLowerTextTarget.TITLE))
        assertEquals(PlayerLowerTextAlign.CENTER, config.textAlignOf(PlayerLowerTextTarget.SUBTITLE))
        assertFalse(config.textAligns.containsKey(PlayerLowerTextTarget.SUBTITLE))
        assertEquals(PlayerLowerTextAlign.CENTER, config.textAlignOf(PlayerLowerTextTarget.LYRICS))
    }

    @Test
    fun titleAndSubtitleAlignAreIndependent() {
        val config = PlayerLowerLayoutConfig.Default
            .withTextAlign(PlayerLowerTextTarget.TITLE, PlayerLowerTextAlign.START)
            .withTextAlign(PlayerLowerTextTarget.SUBTITLE, PlayerLowerTextAlign.END)

        assertEquals(PlayerLowerTextAlign.START, config.textAlignOf(PlayerLowerTextTarget.TITLE))
        assertEquals(PlayerLowerTextAlign.END, config.textAlignOf(PlayerLowerTextTarget.SUBTITLE))
    }

    @Test
    fun defaultShowsEveryControlButtonAndHidingIsPerButton() {
        val config = PlayerLowerLayoutConfig.Default

        PlayerControlButton.entries.forEach { button ->
            assertEquals(true, config.isControlVisible(button))
        }

        val hidden = config
            .withControlVisibility(PlayerControlButton.QUEUE_MODE, false)
            .withControlVisibility(PlayerControlButton.QUEUE, false)
            .normalized()

        assertFalse(hidden.isControlVisible(PlayerControlButton.QUEUE_MODE))
        assertFalse(hidden.isControlVisible(PlayerControlButton.QUEUE))
        assertEquals(true, hidden.isControlVisible(PlayerControlButton.PREVIOUS))
        assertEquals(true, hidden.isControlVisible(PlayerControlButton.PLAY_PAUSE))
        assertEquals(true, hidden.isControlVisible(PlayerControlButton.NEXT))
        assertEquals(
            setOf(PlayerControlButton.QUEUE_MODE, PlayerControlButton.QUEUE),
            hidden.hiddenControls,
        )
    }

    @Test
    fun hidingThenShowingAControlButtonRestoresIt() {
        val config = PlayerLowerLayoutConfig.Default
            .withControlVisibility(PlayerControlButton.PLAY_PAUSE, false)
            .withControlVisibility(PlayerControlButton.PLAY_PAUSE, true)
            .normalized()

        assertEquals(true, config.isControlVisible(PlayerControlButton.PLAY_PAUSE))
        assertEquals(emptySet<PlayerControlButton>(), config.hiddenControls)
    }

    @Test
    fun coverTapAndShadowDefaultOff() {
        val config = PlayerLowerLayoutConfig.Default
            .withCoverTapPlayPause(true)
            .withCoverSwipeEnabled(false)
            .withCoverShadow(true)
            .withCoverShadowStrengthPercent(175)
            .withCoverTapPlayPause(false)
            .normalized()

        assertEquals(false, config.coverTapPlayPause)
        assertEquals(false, config.coverSwipeEnabled)
        assertEquals(true, config.coverShadow)
        assertEquals(175, config.coverShadowStrengthPercent)
    }

    @Test
    fun customControlLayoutKeepsLegacySlotsByDefaultAndCanRedistribute() {
        val default = PlayerLowerLayoutConfig.Default
        assertEquals(false, default.redistributeHiddenControls)
        assertEquals(true, default.coverSwipeEnabled)
        assertEquals(
            PlayerLowerLayoutConfig.DEFAULT_COVER_SHADOW_STRENGTH_PERCENT,
            default.coverShadowStrengthPercent,
        )

        val updated = default
            .withRedistributeHiddenControls(true)
            .withCoverShadowStrengthPercent(999)
            .normalized()

        assertEquals(true, updated.redistributeHiddenControls)
        assertEquals(
            PlayerLowerLayoutConfig.MAX_COVER_SHADOW_STRENGTH_PERCENT,
            updated.coverShadowStrengthPercent,
        )
    }

    @Test
    fun titleAndProgressOptionsDefaultToLegacyAppearanceAndClampSizes() {
        val config = PlayerLowerLayoutConfig.Default
            .copy(
                progressTrackHeightDp = 999,
                progressSpectrumHeightDp = -1,
            )
            .normalized()

        assertEquals(PlayerTitleSubtitleMode.ARTIST_AND_ALBUM, config.titleSubtitleMode)
        assertEquals(PlayerProgressTimeMode.ELAPSED_TOTAL, config.progressTimeMode)
        assertEquals(PlayerLowerLayoutConfig.MAX_PROGRESS_TRACK_HEIGHT_DP, config.progressTrackHeightDp)
        assertEquals(PlayerLowerLayoutConfig.MIN_PROGRESS_SPECTRUM_HEIGHT_DP, config.progressSpectrumHeightDp)
    }

    @Test
    fun titleAndProgressOptionsCanBeUpdatedIndependently() {
        val config = PlayerLowerLayoutConfig.Default
            .withTitleSubtitleMode(PlayerTitleSubtitleMode.HIDDEN)
            .withProgressTimeMode(PlayerProgressTimeMode.ELAPSED_REMAINING)
            .withProgressTrackHeightDp(6)
            .withProgressSpectrumHeightDp(80)

        assertEquals(PlayerTitleSubtitleMode.HIDDEN, config.titleSubtitleMode)
        assertEquals(PlayerProgressTimeMode.ELAPSED_REMAINING, config.progressTimeMode)
        assertEquals(6, config.progressTrackHeightDp)
        assertEquals(80, config.progressSpectrumHeightDp)
    }
}
