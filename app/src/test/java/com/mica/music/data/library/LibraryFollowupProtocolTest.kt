package com.mica.music.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFollowupProtocolTest {
    private val source = SourceIdentityKey.device()

    @Test
    fun autoConfirmedMissingCreatesPlaylistRemovalFollowup() {
        val changeSet = changeSet(
            cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            reason = MembershipRemovalReason.CONFIRMED_MISSING,
            songId = "ms_42",
        )

        val followups = LibraryFollowupProtocol.planPlaylistRemovalFollowups(
            changeSet = changeSet,
            activationEpoch = 7L,
            createdAtMs = 123L,
        )

        assertEquals(1, followups.size)
        val item = followups.single()
        assertEquals(LibraryFollowupProtocol.PLAYLIST_REMOVE_LIBRARY_MEMBERSHIP, item.action)
        assertEquals("ms_42", LibraryFollowupProtocol.playlistRemovalSongId(item.payload))
        assertEquals(7L, item.activationEpoch)
        assertEquals(changeSet.libraryRevision, item.libraryRevision)
    }

    @Test
    fun filteredOutDoesNotPrunePlaylist() {
        val followups = LibraryFollowupProtocol.planPlaylistRemovalFollowups(
            changeSet = changeSet(
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                reason = MembershipRemovalReason.FILTERED_OUT,
                songId = "ms_filtered",
            ),
            activationEpoch = 1L,
            createdAtMs = 1L,
        )

        assertTrue(followups.isEmpty())
    }

    @Test
    fun trashedDoesNotPrunePlaylist() {
        val followups = LibraryFollowupProtocol.planPlaylistRemovalFollowups(
            changeSet = changeSet(
                cause = LibraryOperationCause.MEDIASTORE_FILES_DIRTY,
                reason = MembershipRemovalReason.TRASHED,
                songId = "ms_trashed",
            ),
            activationEpoch = 1L,
            createdAtMs = 1L,
        )

        assertTrue(followups.isEmpty())
    }

    @Test
    fun manualFullScanDoesNotGeneratePlaylistCleanupFromGenericRemoval() {
        val followups = LibraryFollowupProtocol.planPlaylistRemovalFollowups(
            changeSet = changeSet(
                cause = LibraryOperationCause.USER_RESCAN,
                reason = MembershipRemovalReason.CONFIRMED_MISSING,
                songId = "ms_manual",
            ),
            activationEpoch = 1L,
            createdAtMs = 1L,
        )

        assertTrue(followups.isEmpty())
    }

    @Test
    fun missingSongIdCannotCreatePlaylistCleanup() {
        val followups = LibraryFollowupProtocol.planPlaylistRemovalFollowups(
            changeSet = changeSet(
                cause = LibraryOperationCause.FOREGROUND_CATCH_UP,
                reason = MembershipRemovalReason.CONFIRMED_MISSING,
                songId = null,
            ),
            activationEpoch = 1L,
            createdAtMs = 1L,
        )

        assertTrue(followups.isEmpty())
    }

    @Test
    fun safBudgetContinuationIsAnAutoSyncCause() {
        val followups = LibraryFollowupProtocol.planPlaylistRemovalFollowups(
            changeSet = changeSet(
                cause = LibraryOperationCause.SAF_BUDGET_CONTINUATION,
                reason = MembershipRemovalReason.CONFIRMED_MISSING,
                songId = "saf_missing",
            ),
            activationEpoch = 3L,
            createdAtMs = 4L,
        )

        assertEquals(1, followups.size)
        assertEquals("saf_missing", LibraryFollowupProtocol.playlistRemovalSongId(followups.single().payload))
    }

    private fun changeSet(
        cause: LibraryOperationCause,
        reason: MembershipRemovalReason,
        songId: String?,
    ): LibraryChangeSet = LibraryChangeSet(
        libraryRevision = 12L,
        cause = cause,
        addedIds = emptySet(),
        updatedIds = emptySet(),
        membershipChanges = listOf(
            MembershipChange(
                stableObjectKey = songId ?: "stable-missing-id",
                songId = songId,
                reason = reason,
                evidenceRevision = "evidence",
                sourceIdentity = source,
            ),
        ),
    )
}
