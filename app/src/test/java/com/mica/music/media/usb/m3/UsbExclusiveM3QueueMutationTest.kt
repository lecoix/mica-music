package com.mica.music.media.usb.m3

import com.mica.music.media.testPlaybackStack
import com.mica.music.media.usb.protocol.PlaybackFamily
import com.mica.music.media.usb.protocol.PlaybackOccurrence
import com.mica.music.media.usb.shadow.UsbExclusiveShadowAdapterKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class UsbExclusiveM3QueueMutationTest {
    @Test
    fun sameMediaIdReplacementUsesAFreshExactOccurrence() {
        val stack = testPlaybackStack()
        val adapter = stack.newAdapter(UsbExclusiveShadowAdapterKind.FFMPEG_PCM)
        val periodUid = "same-media-period"
        stack.observeTimelinePeriod("same-media", periodUid)
        stack.observeApplicationMedia("same-media")

        val firstEpoch = requireNotNull(stack.beginManualNavigation("same-media", "test-first"))
        val firstOccurrence = PlaybackOccurrence(periodUid, 1L)
        adapter.observeStream(
            firstOccurrence,
            PlaybackFamily.PCM,
            "pcm-target",
            stack.currentTopologyToken(),
        )
        stack.observeCurrentPlayerOccurrence("same-media", firstOccurrence)
        assertEquals(firstOccurrence, stack.snapshot().mutation?.targetOccurrence)

        // A true queue replacement advances the app-owned topology epoch before Exo dispatch.
        // Old seq1 operands therefore cannot bind the replacement mutation.
        stack.advancePlaybackTopology("test-replacement")
        stack.observeTimelinePeriod("same-media", periodUid)
        stack.observeApplicationMedia("same-media")
        val secondEpoch = requireNotNull(stack.beginManualNavigation("same-media", "test-replacement"))
        val secondOccurrence = PlaybackOccurrence(periodUid, 2L)
        adapter.observeStream(secondOccurrence, PlaybackFamily.PCM, "pcm-target", stack.currentTopologyToken())
        stack.observeCurrentPlayerOccurrence("same-media", secondOccurrence)

        assertNotEquals(firstEpoch.mutationId, secondEpoch.mutationId)
        assertEquals(secondOccurrence, stack.snapshot().mutation?.targetOccurrence)
        assertNotNull(stack.snapshot().mutation)
    }
}
