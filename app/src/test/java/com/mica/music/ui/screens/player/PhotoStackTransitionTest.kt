package com.mica.music.ui.screens.player

import com.mica.music.data.TrackSkipDirection
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoStackTransitionTest {

    @Test
    fun steadyStackBindsVisibleCardsToDistinctQueueSongs() {
        val queue = SongFixtures.queue(5)

        val stack = photoStackSteadyStack(
            queue = queue,
            currentIndex = 1,
            currentSong = queue[1],
        )

        assertEquals("song-1", stack.front?.id)
        assertEquals("song-2", stack.middle?.id)
        assertEquals("song-3", stack.back?.id)

        val cards = photoStackSteadyCards(stack)
        assertEquals(
            listOf(
                PhotoStackTransitionSlot.SteadyBack,
                PhotoStackTransitionSlot.SteadyMiddle,
                PhotoStackTransitionSlot.SteadyFront,
            ),
            cards.map { it.slot },
        )
        assertEquals(
            listOf("song-3", "song-2", "song-1"),
            cards.map { it.song.id },
        )
    }

    @Test
    fun nextTransitionKeepsOutgoingCardSeparateFromNewVisibleStack() {
        val queue = SongFixtures.queue(5)

        val plan = photoStackTransitionPlan(
            queue = queue,
            currentIndex = 2,
            currentSong = queue[2],
            settledFrontSong = queue[1],
            direction = TrackSkipDirection.TO_NEXT,
        )

        assertEquals("song-1", plan.leavingFront?.id)
        assertEquals("song-2", plan.stackFront?.id)
        assertEquals("song-3", plan.stackMiddle?.id)
        assertEquals("song-4", plan.emergingBack?.id)
        assertNull(plan.incomingFront)
        assertNull(plan.fadingBack)

        val cards = photoStackTransitionCards(plan)
        assertEquals(
            listOf(
                PhotoStackTransitionSlot.NextEmergingBack,
                PhotoStackTransitionSlot.NextStackMiddle,
                PhotoStackTransitionSlot.NextStackFront,
                PhotoStackTransitionSlot.NextLeavingFront,
            ),
            cards.map { it.slot },
        )
        assertEquals(
            listOf("song-4", "song-3", "song-2", "song-1"),
            cards.map { it.song.id },
        )
    }

    @Test
    fun previousTransitionBringsNewFrontFromLeftWithoutReusingOutgoingCard() {
        val queue = SongFixtures.queue(6)

        val plan = photoStackTransitionPlan(
            queue = queue,
            currentIndex = 2,
            currentSong = queue[2],
            settledFrontSong = queue[3],
            direction = TrackSkipDirection.TO_PREVIOUS,
        )

        assertEquals("song-2", plan.incomingFront?.id)
        assertEquals("song-3", plan.stackMiddle?.id)
        assertEquals("song-4", plan.stackBack?.id)
        assertEquals("song-5", plan.fadingBack?.id)
        assertNull(plan.leavingFront)
        assertNull(plan.stackFront)

        val cards = photoStackTransitionCards(plan)
        assertEquals(
            listOf(
                PhotoStackTransitionSlot.PreviousFadingBack,
                PhotoStackTransitionSlot.PreviousStackBack,
                PhotoStackTransitionSlot.PreviousStackMiddle,
                PhotoStackTransitionSlot.PreviousIncomingFront,
            ),
            cards.map { it.slot },
        )
        assertEquals(
            listOf("song-5", "song-4", "song-3", "song-2"),
            cards.map { it.song.id },
        )
    }
}
