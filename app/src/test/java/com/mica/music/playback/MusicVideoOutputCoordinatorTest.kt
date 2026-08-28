package com.mica.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicVideoOutputCoordinatorTest {
    @Test
    fun newMediaProjectionKeepsCoordinatorSurfaceGenerationButResetsFrameState() {
        val raw = PlaybackVideoState(
            mediaId = "old",
            effective = true,
            status = PlaybackVideoStatus.READY,
            width = 1920,
            height = 1080,
            firstFrameRevision = 8,
            surfaceGeneration = 7,
        )

        val projected = projectMusicVideoState(
            currentMediaId = "new",
            effective = true,
            rawState = raw,
        )

        assertEquals("new", projected.mediaId)
        assertEquals(true, projected.effective)
        assertEquals(PlaybackVideoStatus.UNAVAILABLE, projected.status)
        assertEquals(0, projected.width)
        assertEquals(0, projected.height)
        assertEquals(0, projected.firstFrameRevision)
        assertEquals(7, projected.surfaceGeneration)
    }

    @Test
    fun staleDetachAndFirstFrameCannotAffectNewLease() {
        val states = mutableListOf<PlaybackVideoState>()
        val coordinator = MusicVideoOutputCoordinator(states::add)
        val controller = Any()
        val old = FakePort(Any(), controller, "old")
        val current = FakePort(Any(), controller, "current")
        val oldLease = coordinator.attach(old)

        coordinator.attach(current)
        coordinator.detach(oldLease, old.outputIdentity)
        coordinator.onFirstFrame(controller, "old")

        assertEquals(1, old.detachCount)
        assertEquals(0, current.detachCount)
        assertEquals("current", states.last().mediaId)
        assertEquals(PlaybackVideoStatus.LOADING, states.last().status)
    }

    @Test
    fun controllerDisconnectDetachesOnlyItsOwnSurface() {
        val states = mutableListOf<PlaybackVideoState>()
        val coordinator = MusicVideoOutputCoordinator(states::add)
        val controller = Any()
        val port = FakePort(Any(), controller, "song")
        coordinator.attach(port)

        coordinator.detachForController(Any())
        assertEquals(0, port.detachCount)

        coordinator.detachForController(controller)
        assertEquals(1, port.detachCount)
        assertEquals(PlaybackVideoStatus.UNAVAILABLE, states.last().status)
    }

    @Test
    fun playbackStackRebuildReattachesOnlyCurrentControllerAndSong() {
        val states = mutableListOf<PlaybackVideoState>()
        val coordinator = MusicVideoOutputCoordinator(states::add)
        val controller = Any()
        val rebuiltController = Any()
        val output = Any()
        val port = FakePort(output, controller, "song")
        val oldLease = coordinator.attach(port)

        coordinator.reattachForPlaybackStack(controller, "other")
        coordinator.reattachForPlaybackStack(rebuiltController, "song")
        assertEquals(2, port.attachCount)
        assertEquals(1, states.last().surfaceGeneration)

        // The pre-rebuild lease must not clear the rebound Surface.
        coordinator.detach(oldLease, output)
        assertEquals(0, port.detachCount)

        // Recomposition driven by surfaceGeneration reacquires the active lease id.
        val refreshedLease = coordinator.attach(FakePort(output, rebuiltController, "song"))
        coordinator.detach(refreshedLease, output)
        assertEquals(1, port.detachCount)
        assertEquals(PlaybackVideoStatus.UNAVAILABLE, states.last().status)
        assertEquals(2, states.last().surfaceGeneration)

        assertEquals(rebuiltController, port.controllerIdentity)
    }

    private class FakePort(
        override val outputIdentity: Any,
        initialControllerIdentity: Any,
        override val mediaId: String,
    ) : MusicVideoOutputLeasePort {
        var attachCount = 0
        var detachCount = 0
        override fun attach() { attachCount++ }
        override fun detach() { detachCount++ }
        override fun rebind(controllerIdentity: Any): Boolean {
            boundController = controllerIdentity
            return true
        }

        private var boundController: Any = initialControllerIdentity
        override val controllerIdentity: Any
            get() = boundController
    }
}
