package com.mica.music.data.library

import android.provider.MediaStore
import com.mica.music.data.scanner.DeviceMediaStoreChannelCapability
import com.mica.music.data.scanner.DeviceMediaStorePresenceCapabilityProfile
import com.mica.music.data.scanner.DiscoveryCompleteness
import com.mica.music.data.scanner.DiscoveryPartitionStatus
import com.mica.music.data.scanner.DiscoveryPartitions
import com.mica.music.data.scanner.DiscoveryReport
import com.mica.music.data.scanner.LibraryEligibility
import com.mica.music.data.scanner.PresenceEntry
import com.mica.music.data.scanner.PresenceInventory
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMembershipDecisionPolicyTest {
    private val source = SourceIdentityKey.device()

    @Test
    fun partialFilesCoverageCannotConfirmMissingDeviceSong() {
        val presence = PresenceInventory.fromEntries(
            entries = emptyList(),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    DiscoveryPartitions.MEDIASTORE_AUDIO,
                    DiscoveryCompleteness.COMPLETE,
                ),
                DiscoveryPartitionStatus(
                    DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK,
                    DiscoveryCompleteness.PARTIAL,
                ),
            ),
        )

        val decision = LibraryMembershipDecisionPolicy.classifyDeviceObject(
            stableObjectKey = "ms_1",
            songId = "ms_1",
            sourceIdentity = source,
            presence = presence,
            evidenceRevision = "coverage-1",
        )

        assertEquals(MembershipDecision.Keep, decision)
    }

    @Test
    fun pendingPresenceNeverBecomesMissing() {
        val presence = completePresence(
            PresenceEntry(
                stableObjectKey = "ms_2",
                partitionKey = DiscoveryPartitions.MEDIASTORE_AUDIO,
                eligibility = LibraryEligibility.PENDING,
                evidenceRevision = "pending",
            ),
        )

        val decision = LibraryMembershipDecisionPolicy.classifyDeviceObject(
            "ms_2", "ms_2", source, presence, "coverage-2",
        )

        assertEquals(MembershipDecision.Keep, decision)
    }

    @Test
    fun trashedPresenceProducesTrashedReasonNotMissing() {
        val presence = completePresence(
            PresenceEntry(
                stableObjectKey = "ms_3",
                partitionKey = DiscoveryPartitions.MEDIASTORE_AUDIO,
                eligibility = LibraryEligibility.TRASHED,
                evidenceRevision = "trash",
            ),
        )

        val decision = LibraryMembershipDecisionPolicy.classifyDeviceObject(
            "ms_3", "ms_3", source, presence, "coverage-3",
        ) as MembershipDecision.Change

        assertEquals(MembershipRemovalReason.TRASHED, decision.change.reason)
    }

    @Test
    fun filteredPresenceProducesFilteredReasonNotMissing() {
        val presence = completePresence(
            PresenceEntry(
                stableObjectKey = "ms_4",
                partitionKey = DiscoveryPartitions.MEDIASTORE_AUDIO,
                eligibility = LibraryEligibility.FILTERED_OUT,
                evidenceRevision = "filter",
            ),
        )

        val decision = LibraryMembershipDecisionPolicy.classifyDeviceObject(
            "ms_4", "ms_4", source, presence, "coverage-4",
        ) as MembershipDecision.Change

        assertEquals(MembershipRemovalReason.FILTERED_OUT, decision.change.reason)
    }

    @Test
    fun completeAbsenceAcrossBothDevicePartitionsConfirmsMissing() {
        val presence = completePresence()

        val decision = LibraryMembershipDecisionPolicy.classifyDeviceObject(
            "ms_5", "ms_5", source, presence, "coverage-5",
        ) as MembershipDecision.Change

        assertEquals(MembershipRemovalReason.CONFIRMED_MISSING, decision.change.reason)
        assertEquals("coverage-5", decision.change.evidenceRevision)
    }

    @Test
    fun completePartitionsStillCannotConfirmMissingWhenMediaStoreCapabilityIsUnsafe() {
        val safeAudio = mediaStoreCapability(
            DiscoveryPartitions.MEDIASTORE_AUDIO,
            permissionComplete = true,
        )
        val unsafeFiles = mediaStoreCapability(
            DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK,
            permissionComplete = false,
        )
        val presence = completePresence().copy(
            deviceMediaStoreCapabilityProfile = DeviceMediaStorePresenceCapabilityProfile(
                channels = mapOf(
                    safeAudio.partitionKey to safeAudio,
                    unsafeFiles.partitionKey to unsafeFiles,
                ),
            ),
        )

        val decision = LibraryMembershipDecisionPolicy.classifyDeviceObject(
            "ms_unsafe",
            "ms_unsafe",
            source,
            presence,
            "coverage-capability",
        )

        assertEquals(MembershipDecision.Keep, decision)
    }

    @Test
    fun plannerKeepsAllRowsWhenRequiredDevicePartitionIsPartial() {
        val previous = listOf(SongFixtures.song("ms_1"))
        val presence = PresenceInventory.fromEntries(
            entries = emptyList(),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    DiscoveryPartitions.MEDIASTORE_AUDIO,
                    DiscoveryCompleteness.COMPLETE,
                ),
                DiscoveryPartitionStatus(
                    DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK,
                    DiscoveryCompleteness.PARTIAL,
                ),
            ),
        )

        val plan = AutoSyncMembershipPlanner.planDevice(
            previousSongs = previous,
            sourceIdentity = source,
            presence = presence,
            absenceEvidenceRevision = "coverage-partial",
        )

        assertEquals(AutoSyncMembershipPlan.Apply(emptyList()), plan)
    }

    @Test
    fun plannerProducesConfirmedMissingForSmallCompleteAbsence() {
        val previous = listOf(
            SongFixtures.song("ms_1"),
            SongFixtures.song("ms_2"),
        )
        val presence = completePresence(
            PresenceEntry(
                stableObjectKey = "ms_2",
                partitionKey = DiscoveryPartitions.MEDIASTORE_AUDIO,
                eligibility = LibraryEligibility.ELIGIBLE,
                evidenceRevision = "keep",
            ),
        )

        val plan = AutoSyncMembershipPlanner.planDevice(
            previousSongs = previous,
            sourceIdentity = source,
            presence = presence,
            absenceEvidenceRevision = "coverage-complete",
        ) as AutoSyncMembershipPlan.Apply

        assertEquals(1, plan.membershipChanges.size)
        assertEquals("ms_1", plan.membershipChanges.single().stableObjectKey)
        assertEquals(
            MembershipRemovalReason.CONFIRMED_MISSING,
            plan.membershipChanges.single().reason,
        )
    }

    @Test
    fun plannerQuarantinesInventoryCollapseInsteadOfReturningMassRemoval() {
        val previous = List(100) { index -> SongFixtures.song("ms_${index + 1}") }

        val plan = AutoSyncMembershipPlanner.planDevice(
            previousSongs = previous,
            sourceIdentity = source,
            presence = completePresence(),
            absenceEvidenceRevision = "coverage-collapse",
        ) as AutoSyncMembershipPlan.Quarantine

        assertEquals(MassDeletionQuarantineReason.INVENTORY_COLLAPSE, plan.reason)
        assertEquals(100, plan.membershipChanges.size)
    }

    @Test
    fun plannerAllowsFilteredRemovalWithoutTreatingItAsMissingBatch() {
        val previous = List(60) { index -> SongFixtures.song("ms_${index + 1}") }
        val filtered = previous.map { song ->
            PresenceEntry(
                stableObjectKey = song.id,
                partitionKey = DiscoveryPartitions.MEDIASTORE_AUDIO,
                eligibility = LibraryEligibility.FILTERED_OUT,
                evidenceRevision = "filtered",
            )
        }

        val plan = AutoSyncMembershipPlanner.planDevice(
            previousSongs = previous,
            sourceIdentity = source,
            presence = completePresence(*filtered.toTypedArray()),
            absenceEvidenceRevision = "coverage-filtered",
        ) as AutoSyncMembershipPlan.Apply

        assertEquals(60, plan.membershipChanges.size)
        assertTrue(plan.membershipChanges.all { it.reason == MembershipRemovalReason.FILTERED_OUT })
    }
    @Test
    fun safPartialInventoryNeverProducesDestructiveMembershipChange() {
        val previous = listOf(SongFixtures.song("doc-1"))

        val plan = AutoSyncMembershipPlanner.planSaf(
            previousSongs = previous,
            sourceIdentity = SourceIdentityKey.folder("content://provider/tree/music"),
            observedStableObjectKeys = emptySet(),
            discoveryComplete = false,
            absenceEvidenceRevision = "saf-partial",
        )

        assertEquals(AutoSyncMembershipPlan.Apply(emptyList()), plan)
    }

    @Test
    fun safCompleteSmallAbsenceProducesConfirmedMissing() {
        val previous = listOf(
            SongFixtures.song("doc-1"),
            SongFixtures.song("doc-2"),
        )

        val plan = AutoSyncMembershipPlanner.planSaf(
            previousSongs = previous,
            sourceIdentity = SourceIdentityKey.folder("content://provider/tree/music"),
            observedStableObjectKeys = setOf("doc-2"),
            discoveryComplete = true,
            absenceEvidenceRevision = "saf-complete",
        ) as AutoSyncMembershipPlan.Apply

        assertEquals(listOf("doc-1"), plan.membershipChanges.map { it.stableObjectKey })
        assertEquals(MembershipRemovalReason.CONFIRMED_MISSING, plan.membershipChanges.single().reason)
        assertEquals("saf-complete", plan.membershipChanges.single().evidenceRevision)
    }

    @Test
    fun safCompleteInventoryCollapseIsQuarantined() {
        val previous = List(100) { index -> SongFixtures.song("doc-${index + 1}") }

        val plan = AutoSyncMembershipPlanner.planSaf(
            previousSongs = previous,
            sourceIdentity = SourceIdentityKey.folder("content://provider/tree/music"),
            observedStableObjectKeys = emptySet(),
            discoveryComplete = true,
            absenceEvidenceRevision = "saf-collapse",
        ) as AutoSyncMembershipPlan.Quarantine

        assertEquals(MassDeletionQuarantineReason.INVENTORY_COLLAPSE, plan.reason)
        assertEquals(100, plan.membershipChanges.size)
    }

    @Test
    fun safCompleteLargeUnverifiedRemovalBatchIsQuarantined() {
        val previous = List(100) { index -> SongFixtures.song("doc-" + (index + 1)) }

        val plan = AutoSyncMembershipPlanner.planSaf(
            previousSongs = previous,
            sourceIdentity = SourceIdentityKey.folder("content://provider/tree/music"),
            observedStableObjectKeys = setOf("doc-100"),
            discoveryComplete = true,
            absenceEvidenceRevision = "saf-large",
        ) as AutoSyncMembershipPlan.Quarantine

        assertEquals(MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH, plan.reason)
        assertEquals(99, plan.membershipChanges.size)
    }

    @Test
    fun tenThousandToZeroInventoryIsQuarantined() {
        val presence = completePresence()
        val changes = (1..10_000).map { index -> missingChange(index) }

        val decision = MassDeletionGuard.evaluate(
            previousLibraryCount = 10_000,
            presence = presence,
            membershipChanges = changes,
        )

        assertEquals(
            MassDeletionDecision.Quarantine(MassDeletionQuarantineReason.INVENTORY_COLLAPSE),
            decision,
        )
    }

    @Test
    fun largeUnverifiedRemovalBatchIsQuarantinedEvenWhenInventoryNotEmpty() {
        val presence = completePresence(
            PresenceEntry(
                stableObjectKey = "ms_keep",
                partitionKey = DiscoveryPartitions.MEDIASTORE_AUDIO,
                eligibility = LibraryEligibility.ELIGIBLE,
                evidenceRevision = "keep",
            ),
        )
        val changes = (1..2_500).map { index -> missingChange(index) }

        val decision = MassDeletionGuard.evaluate(
            previousLibraryCount = 10_000,
            presence = presence,
            membershipChanges = changes,
        )

        assertEquals(
            MassDeletionDecision.Quarantine(MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH),
            decision,
        )
    }

    @Test
    fun smallVerifiedObjectRemovalCanDeleteLastSong() {
        val presence = completePresence()
        val change = missingChange(1)

        val decision = MassDeletionGuard.evaluate(
            previousLibraryCount = 1,
            presence = presence,
            membershipChanges = listOf(change),
            independentlyVerifiedMissingKeys = setOf(change.stableObjectKey),
        )

        assertEquals(MassDeletionDecision.Allow, decision)
    }

    @Test
    fun fullyTargetVerifiedLargeBatchMayPassGuard() {
        val presence = completePresence(
            PresenceEntry(
                stableObjectKey = "ms_keep",
                partitionKey = DiscoveryPartitions.MEDIASTORE_AUDIO,
                eligibility = LibraryEligibility.ELIGIBLE,
                evidenceRevision = "keep",
            ),
        )
        val changes = (1..100).map { index -> missingChange(index) }

        val decision = MassDeletionGuard.evaluate(
            previousLibraryCount = 100,
            presence = presence,
            membershipChanges = changes,
            independentlyVerifiedMissingKeys = changes.mapTo(linkedSetOf()) { it.stableObjectKey },
        )

        assertEquals(MassDeletionDecision.Allow, decision)
    }

    @Test
    fun guardThresholdIsFrozenAtFiftyAndTwentyFivePercent() {
        assertEquals(50, MassDeletionGuard.MIN_LARGE_BATCH_COUNT)
        assertEquals(0.25, MassDeletionGuard.LARGE_BATCH_RATIO, 0.0)
        assertTrue(MassDeletionGuard.MIN_LARGE_BATCH_COUNT > 0)
    }

    private fun completePresence(vararg entries: PresenceEntry): PresenceInventory =
        PresenceInventory.fromEntries(
            entries = entries.toList(),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    DiscoveryPartitions.MEDIASTORE_AUDIO,
                    DiscoveryCompleteness.COMPLETE,
                ),
                DiscoveryPartitionStatus(
                    DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK,
                    DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )

    private fun mediaStoreCapability(
        partitionKey: String,
        permissionComplete: Boolean,
    ) = DeviceMediaStoreChannelCapability(
        partitionKey = partitionKey,
        columnsAvailable = setOf(
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.IS_TRASHED,
        ),
        rowInclusionSemanticsKnown = true,
        permissionScope = if (permissionComplete) {
            "READ_MEDIA_AUDIO=granted"
        } else {
            "READ_MEDIA_AUDIO=denied"
        },
        permissionScopeComplete = permissionComplete,
        volumeScope = "external-aggregate",
        apiLevel = 35,
    )

    private fun missingChange(index: Int): MembershipChange = MembershipChange(
        stableObjectKey = "ms_$index",
        songId = "ms_$index",
        reason = MembershipRemovalReason.CONFIRMED_MISSING,
        evidenceRevision = "coverage-$index",
        sourceIdentity = source,
    )
}
