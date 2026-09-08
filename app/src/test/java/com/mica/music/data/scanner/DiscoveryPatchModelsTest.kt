package com.mica.music.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryPatchModelsTest {

    @Test
    fun unknownPreservesCurrentValue() {
        val observed: Observed<String> = Observed.Unknown
        assertEquals("old", observed.applyTo("old"))
    }

    @Test
    fun presentReplacesCurrentValue() {
        val observed: Observed<String> = Observed.Present("new")
        assertEquals("new", observed.applyTo("old"))
    }

    @Test
    fun absentConfirmedClearsCurrentValue() {
        val observed: Observed<String> = Observed.AbsentConfirmed
        assertNull(observed.applyTo("old"))
    }

    @Test
    fun emptyAutoDeltaUsesCheckpointOnlyPath() {
        assertEquals(
            AutoSyncPublicationDecision.CHECKPOINT_ONLY,
            AutoSyncVisibleDelta().publicationDecision(),
        )
    }

    @Test
    fun anyVisibleAutoDeltaRequiresSnapshotPublication() {
        val deltas = listOf(
            AutoSyncVisibleDelta(addedIds = setOf("added")),
            AutoSyncVisibleDelta(updatedIds = setOf("updated")),
            AutoSyncVisibleDelta(removedStableObjectKeys = setOf("removed")),
            AutoSyncVisibleDelta(derivedInvalidationKeys = setOf("mv:folder/song")),
        )

        deltas.forEach { delta ->
            assertEquals(
                AutoSyncPublicationDecision.SNAPSHOT_PUBLICATION,
                delta.publicationDecision(),
            )
        }
    }

    @Test
    fun missingPresenceIsOnlyConfirmedByCompleteOwningPartition() {
        val partition = DiscoveryPartitions.MEDIASTORE_AUDIO
        val partial = PresenceInventory.fromEntries(
            entries = emptyList(),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(partition, DiscoveryCompleteness.PARTIAL),
            ),
        )
        val complete = partial.copy(
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(partition, DiscoveryCompleteness.COMPLETE),
            ),
        )

        assertFalse(partial.absenceConfirmed("song-1", partition))
        assertTrue(complete.absenceConfirmed("song-1", partition))
    }

    @Test
    fun presentFilteredOrPendingObjectIsNeverPhysicalAbsence() {
        val partition = DiscoveryPartitions.MEDIASTORE_AUDIO
        val filtered = PresenceEntry(
            stableObjectKey = "song-filtered",
            partitionKey = partition,
            eligibility = LibraryEligibility.FILTERED_OUT,
            evidenceRevision = "rev-1",
        )
        val pending = PresenceEntry(
            stableObjectKey = "song-pending",
            partitionKey = partition,
            eligibility = LibraryEligibility.PENDING,
            evidenceRevision = "rev-2",
        )
        val inventory = PresenceInventory.fromEntries(
            entries = listOf(filtered, pending),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(partition, DiscoveryCompleteness.COMPLETE),
            ),
        )

        assertEquals(
            LibraryEligibility.FILTERED_OUT,
            inventory.entry("song-filtered", partition)?.eligibility,
        )
        assertEquals(
            LibraryEligibility.PENDING,
            inventory.entry("song-pending", partition)?.eligibility,
        )
        assertFalse(inventory.absenceConfirmed("song-filtered", partition))
        assertFalse(inventory.absenceConfirmed("song-pending", partition))
    }
}
