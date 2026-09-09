package com.mica.music.data.library

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.*
import org.junit.Test

class AutoSyncDurationFilterTest {
    @Test
    fun shortNewSongIsNotPublishedAndExistingShortSongIsFilteredNotDeleted() {
        val old = SongFixtures.song("old").copy(durationSec = 4)
        val fresh = SongFixtures.song("new").copy(durationSec = 59)
        val boundary = SongFixtures.song("boundary").copy(durationSec = 60)
        val unknown = SongFixtures.song("unknown").copy(durationSec = 0)
        val next = linkedMapOf(old.id to old, fresh.id to fresh, boundary.id to boundary, unknown.id to unknown)
        val changes = applyAutoSyncDurationFilter(next, setOf(old.id), SourceIdentityKey.device(), 60_000)
        assertEquals(setOf(boundary.id, unknown.id), next.keys)
        assertEquals(old.id, changes.single().songId)
        assertEquals(MembershipRemovalReason.FILTERED_OUT, changes.single().reason)
        assertTrue(LibraryFollowupProtocol.planPlaylistRemovalFollowups(
            LibraryChangeSet(1L, LibraryOperationCause.SAF_PERIODIC_VERIFY, emptySet(), emptySet(), changes),
            activationEpoch = 1L, createdAtMs = 0L,
        ).isEmpty())
        assertTrue(LibraryFollowupProtocol.planPlaylistRemovalFollowups(
            LibraryChangeSet(1L, LibraryOperationCause.SAF_PERIODIC_VERIFY, emptySet(), emptySet(), changes),
            activationEpoch = 1L, createdAtMs = 0L,
        ).isEmpty())
    }

    @Test
    fun zeroMinimumAllowsShortSongs() {
        val short = SongFixtures.song("short").copy(durationSec = 4)
        val next = linkedMapOf(short.id to short)
        assertTrue(applyAutoSyncDurationFilter(next, emptySet(), SourceIdentityKey.device(), 0).isEmpty())
        assertEquals(listOf(short), next.values.toList())
    }
}
