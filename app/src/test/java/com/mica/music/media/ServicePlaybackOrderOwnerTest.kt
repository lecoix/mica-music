package com.mica.music.media

import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.mica.music.audio.AudioQualityMode
import com.mica.music.data.playback.ServicePlaybackSnapshot
import com.mica.music.data.playback.ServicePlaybackStateStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServicePlaybackOrderOwnerTest {
    private fun request(physical: List<String>, order: List<String>, token: Long) =
        checkNotNull(PlaybackShuffleSessionCommand.decode(PlaybackShuffleSessionCommand.command,
            checkNotNull(PlaybackShuffleSessionCommand.encodeOrder(physical, order, physical, true, token))))

    @Test fun postedOldCommandCannotReplaceNewAcceptedOrderEvenAfterABA() {
        val owner = ServicePlaybackOrderOwner()
        val ids = listOf("a", "b", "c")
        val old = request(ids, listOf("b", "a", "c"), PlaybackShuffleSessionCommand.invalidateRequests())
        // A -> clear -> A is deliberately equal in content. Only the request token distinguishes it.
        PlaybackShuffleSessionCommand.invalidateRequests()
        owner.project(emptyList())
        val newest = request(ids, listOf("c", "b", "a"), PlaybackShuffleSessionCommand.invalidateRequests())
        owner.accept(newest, ids, "b")
        assertNull(owner.accept(old, ids, "b"))
        assertEquals(listOf("c", "b", "a"), owner.project(ids)?.playbackIds)
        // Assert the actual durable projection after releasing the old command.
        val store = ServicePlaybackStateStore(ApplicationProvider.getApplicationContext())
        val accepted = checkNotNull(owner.project(ids))
        try {
            store.save(ServicePlaybackSnapshot(ids, 1, 12000, Player.REPEAT_MODE_OFF,
                true, false, AudioQualityMode.HIFI, playbackOrderIds = accepted.playbackIds,
                sourceOrderIds = accepted.sourceIds), sync = true)
            assertEquals(accepted.playbackIds, store.load()?.playbackOrderIds)
            assertEquals("b", store.load()?.currentSongId)
        } finally { store.clear(sync = true) }
    }

    @Test fun physicalVersionMismatchHasNoSideEffect() {
        val owner = ServicePlaybackOrderOwner()
        val ids = listOf("a", "b", "c")
        val pending = request(ids, ids.reversed(), PlaybackShuffleSessionCommand.invalidateRequests())
        assertNull(owner.accept(pending, listOf("b", "a", "c"), "a"))
        assertNull(owner.project(ids))
    }

    @Test fun cursorAndStackChangesPreserveAcceptedOrder() {
        val owner = ServicePlaybackOrderOwner()
        val ids = listOf("a", "b", "c")
        val shuffled = listOf("b", "c", "a")
        owner.accept(request(ids, shuffled, PlaybackShuffleSessionCommand.invalidateRequests()), ids, "b")
        assertEquals(shuffled, owner.project(ids.reversed())?.playbackIds)
        owner.restore(ids, ids, ids, false) // Stale stack restore cannot replace a live accepted order.
        assertEquals(shuffled, owner.project(ids)?.playbackIds)
    }

    @Test fun malformedPermutationIsRejected() {
        val ids = listOf("a", "b", "c")
        assertNull(PlaybackShuffleSessionCommand.encodeOrder(ids, listOf("a", "a", "c"), ids, true, 1))
        val args = checkNotNull(PlaybackShuffleSessionCommand.encodeOrder(ids, ids, ids, true, 1))
        args.putIntArray("order", intArrayOf(0, 1, 9))
        assertNull(PlaybackShuffleSessionCommand.decode(PlaybackShuffleSessionCommand.command, args))
    }

    @Test fun cursorFromPreviousRevisionCannotSeekNewAcceptedOrder() {
        val store = ServicePlaybackStateStore(ApplicationProvider.getApplicationContext())
        try {
            store.save(ServicePlaybackSnapshot(listOf("a", "b"), 0, 45000L, Player.REPEAT_MODE_OFF,
                false, false, AudioQualityMode.HIFI, queueRevision = 1L), sync = true)
            store.saveQueue(com.mica.music.data.playback.ServiceQueueSnapshot(
                listOf("a", "b"), 2L, playbackOrderIds = listOf("b", "a"),
                sourceOrderIds = listOf("a", "b"), appShuffleEnabled = true, orderCurrentSongId = "b"), sync = true)
            assertEquals(listOf("b", "a"), store.load()?.playbackOrderIds)
            assertEquals("b", store.load()?.currentSongId)
            assertEquals(0L, store.load()?.positionMs)
            assertEquals(true, store.load()?.shuffleEnabled)
            store.migrateSongIds(mapOf("b" to "new-b"))
            assertEquals(listOf("new-b", "a"), store.load()?.playbackOrderIds)
            assertEquals(listOf("a", "new-b"), store.load()?.sourceOrderIds)
        } finally { store.clear(sync = true) }
    }
}
