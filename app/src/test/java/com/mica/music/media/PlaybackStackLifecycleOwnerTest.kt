package com.mica.music.media

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackStackLifecycleOwnerTest {
    @Test
    fun installAttachesOwnersBeforePublishingPlayer() {
        val events = mutableListOf<String>()
        val stack = playbackStack()
        val owner = PlaybackStackLifecycleOwner { published ->
            assertSame(stack.compositePlayer, published)
            events += "publish"
        }

        owner.install(
            stack = stack,
            attachOwners = { events += "attach" },
            detachOwners = { events += "detach" },
        )

        assertEquals(listOf("attach", "publish"), events)
        assertSame(stack, owner.activeStack)
        assertSame(stack.exoPlayer, owner.exoPlayer)
        assertSame(stack.compositePlayer, owner.player)
    }

    @Test
    fun retireDetachesOwnersBeforeReleasingPlayerAndClearsAuthority() {
        val events = mutableListOf<String>()
        val exo = mockk<ExoPlayer>(relaxed = true)
        every { exo.release() } answers { events += "release" }
        val stack = playbackStack(exo)
        val owner = PlaybackStackLifecycleOwner { }
        owner.install(stack, attachOwners = {}, detachOwners = {})

        owner.retire(
            detachOwners = {
                assertFalse(owner.isActive(stack))
                events += "detach"
            },
            beforePlayerRelease = { events += "session" },
        )

        assertEquals(listOf("detach", "session", "release"), events)
        assertFalse(owner.hasActiveStack)
        assertNull(owner.activeStack)
        assertNull(owner.exoPlayer)
        assertNull(owner.player)
    }

    @Test
    fun captureHandoffSnapshotsOnlyTheActivePlaybackStack() {
        val item = MediaItem.Builder().setMediaId("track-1").setUri("file:///track-1.flac").build()
        val player = mockk<MicaCompositePlayer>(relaxed = true)
        every { player.playbackQueueSnapshot() } returns PlaybackQueueSnapshot(listOf(item), 0, 7L)
        every { player.currentPosition } returns 1_234L
        every { player.playWhenReady } returns true
        every { player.repeatMode } returns 2
        every { player.playbackParameters } returns PlaybackParameters(1.1f, 1.0f)
        every { player.volume } returns 0.65f
        val stack = playbackStack(player = player)
        val owner = PlaybackStackLifecycleOwner { }
        owner.install(stack, attachOwners = {}, detachOwners = {})

        val handoff = requireNotNull(owner.captureHandoff())

        assertEquals(listOf(item), handoff.items)
        assertEquals(0, handoff.currentIndex)
        assertEquals(1_234L, handoff.positionMs)
        assertTrue(handoff.playWhenReady)
        assertEquals(2, handoff.repeatMode)
        assertEquals(1.1f, handoff.playbackParameters.speed)
        assertEquals(0.65f, handoff.volume)
    }

    @Test
    fun replacementMakesRetiredStackFailActiveIdentityChecks() {
        val first = playbackStack()
        val second = playbackStack()
        val owner = PlaybackStackLifecycleOwner { }
        owner.install(first, attachOwners = {}, detachOwners = {})

        owner.retire(detachOwners = {})
        owner.install(second, attachOwners = {}, detachOwners = {})

        assertFalse(owner.isActive(first))
        assertTrue(owner.isActive(second))
    }

    @Test
    fun retireWithoutActiveStackStillRunsServiceTeardownHook() {
        val events = mutableListOf<String>()
        val owner = PlaybackStackLifecycleOwner { }

        owner.retire(
            detachOwners = { events += "detach" },
            beforePlayerRelease = { events += "session" },
        )

        assertEquals(listOf("session"), events)
    }

    @Test
    fun failedAttachmentRollsBackWithoutPublishingHalfInstalledPlayer() {
        val events = mutableListOf<String>()
        val exo = mockk<ExoPlayer>(relaxed = true)
        every { exo.release() } answers { events += "release" }
        val stack = playbackStack(exo)
        val owner = PlaybackStackLifecycleOwner { events += "publish" }

        val failure = assertThrows(IllegalStateException::class.java) {
            owner.install(
                stack = stack,
                attachOwners = {
                    events += "attach"
                    error("attach failed")
                },
                detachOwners = {
                    assertFalse(owner.isActive(stack))
                    events += "detach"
                },
            )
        }

        assertEquals("attach failed", failure.message)
        assertEquals(listOf("attach", "detach", "release"), events)
        assertFalse(owner.hasActiveStack)
    }

    private fun playbackStack(
        exo: ExoPlayer = mockk(relaxed = true),
        player: MicaCompositePlayer = mockk(relaxed = true),
    ): ExoPlaybackStack = ExoPlaybackStack(
        exoPlayer = exo,
        compositePlayer = player,
        applyAudioFocusSetting = {},
    )
}