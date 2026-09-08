package com.mica.music.data.library

import com.mica.music.data.Song
import com.mica.music.data.scanner.DeviceDeltaCandidatePlan
import com.mica.music.data.scanner.LibraryEligibility
import com.mica.music.data.scanner.PresenceInventory

internal enum class DeviceShadowFilterOutcome {
    ELIGIBLE,
    PENDING_KEEP,
    PENDING_NEW_SUPPRESSED,
    FILTERED_OUT,
    TRASHED,
    CONFIRMED_MISSING,
    UNKNOWN_KEEP,
}

internal data class DeviceShadowMembershipContradiction(
    val stableObjectKey: String,
    val expectedReason: MembershipRemovalReason?,
    val plannedReason: MembershipRemovalReason?,
    val expectedEvidenceRevision: String?,
    val plannedEvidenceRevision: String?,
    val expectedSourceIdentity: SourceIdentityKey?,
    val plannedSourceIdentity: SourceIdentityKey?,
)

internal data class DeviceShadowMembershipAudit(
    val outcomesByStableObjectKey: Map<String, DeviceShadowFilterOutcome>,
    val expectedRemovalChanges: Map<String, MembershipChange>,
    val plannedRemovalChanges: Map<String, MembershipChange>,
    val contradictions: List<DeviceShadowMembershipContradiction>,
    val pendingKeepKeys: Set<String>,
    val quarantined: Boolean,
) {
    val fullyConsistent: Boolean
        get() = contradictions.isEmpty()

    val removalReasonCounts: Map<MembershipRemovalReason, Int>
        get() = expectedRemovalChanges.values
            .groupingBy(MembershipChange::reason)
            .eachCount()
}

internal object DeviceShadowMembershipAuditor {

    fun audit(
        previousSongs: List<Song>,
        candidates: DeviceDeltaCandidatePlan,
        presence: PresenceInventory,
        membershipPlan: AutoSyncMembershipPlan,
        sourceIdentity: SourceIdentityKey,
        evidenceRevision: String,
    ): DeviceShadowMembershipAudit {
        val outcomes = linkedMapOf<String, DeviceShadowFilterOutcome>()
        val expectedRemovals = linkedMapOf<String, MembershipChange>()
        val previousKeys = previousSongs
            .mapTo(linkedSetOf(), ::userExclusionStableObjectKey)

        previousSongs.forEach { song ->
            val stableObjectKey = userExclusionStableObjectKey(song)
            when (
                val classification = LibraryMembershipDecisionPolicy.classifyDeviceObjectDetailed(
                    stableObjectKey = stableObjectKey,
                    songId = song.id,
                    sourceIdentity = sourceIdentity,
                    presence = presence,
                    evidenceRevision = evidenceRevision,
                )
            ) {
                is DeviceMembershipClassification.Keep -> {
                    outcomes[stableObjectKey] = when (classification.reason) {
                        DeviceMembershipKeepReason.ELIGIBLE ->
                            DeviceShadowFilterOutcome.ELIGIBLE
                        DeviceMembershipKeepReason.PENDING ->
                            DeviceShadowFilterOutcome.PENDING_KEEP
                        DeviceMembershipKeepReason.UNCERTAIN ->
                            DeviceShadowFilterOutcome.UNKNOWN_KEEP
                    }
                }

                is DeviceMembershipClassification.Remove -> {
                    val change = classification.change
                    expectedRemovals[stableObjectKey] = change
                    outcomes[stableObjectKey] = when (change.reason) {
                        MembershipRemovalReason.FILTERED_OUT ->
                            DeviceShadowFilterOutcome.FILTERED_OUT
                        MembershipRemovalReason.TRASHED ->
                            DeviceShadowFilterOutcome.TRASHED
                        MembershipRemovalReason.CONFIRMED_MISSING ->
                            DeviceShadowFilterOutcome.CONFIRMED_MISSING
                        MembershipRemovalReason.SOURCE_REPLACED,
                        MembershipRemovalReason.USER_EXCLUDED,
                        MembershipRemovalReason.UNAVAILABLE,
                        -> error(
                            "DEVICE presence classifier emitted non-presence removal: " +
                                "${change.reason}",
                        )
                    }
                }
            }
        }

        candidates.audioCandidates.forEach { candidate ->
            val key = candidate.canonicalStableObjectKey
            if (key in previousKeys) return@forEach
            outcomes[key] = when (candidate.primaryRow.eligibility) {
                LibraryEligibility.ELIGIBLE -> DeviceShadowFilterOutcome.ELIGIBLE
                LibraryEligibility.PENDING -> DeviceShadowFilterOutcome.PENDING_NEW_SUPPRESSED
                LibraryEligibility.FILTERED_OUT -> DeviceShadowFilterOutcome.FILTERED_OUT
                LibraryEligibility.TRASHED -> DeviceShadowFilterOutcome.TRASHED
            }
        }

        val plannedChanges = when (membershipPlan) {
            is AutoSyncMembershipPlan.Apply -> membershipPlan.membershipChanges
            is AutoSyncMembershipPlan.Quarantine -> membershipPlan.membershipChanges
        }
        val duplicatePlannedKeys = plannedChanges
            .groupingBy(MembershipChange::stableObjectKey)
            .eachCount()
            .filterValues { it > 1 }
            .keys
        val plannedByKey = plannedChanges.associateBy(MembershipChange::stableObjectKey)
        val allRemovalKeys = (expectedRemovals.keys + plannedByKey.keys + duplicatePlannedKeys)
            .toSortedSet()
        val contradictions = allRemovalKeys.mapNotNull { key ->
            val expected = expectedRemovals[key]
            val planned = plannedByKey[key]
            val mismatch =
                key in duplicatePlannedKeys ||
                    expected?.reason != planned?.reason ||
                    expected?.evidenceRevision != planned?.evidenceRevision ||
                    expected?.sourceIdentity != planned?.sourceIdentity ||
                    expected?.songId != planned?.songId
            if (!mismatch) {
                null
            } else {
                DeviceShadowMembershipContradiction(
                    stableObjectKey = key,
                    expectedReason = expected?.reason,
                    plannedReason = planned?.reason,
                    expectedEvidenceRevision = expected?.evidenceRevision,
                    plannedEvidenceRevision = planned?.evidenceRevision,
                    expectedSourceIdentity = expected?.sourceIdentity,
                    plannedSourceIdentity = planned?.sourceIdentity,
                )
            }
        }

        return DeviceShadowMembershipAudit(
            outcomesByStableObjectKey = outcomes.toMap(),
            expectedRemovalChanges = expectedRemovals.toMap(),
            plannedRemovalChanges = plannedByKey,
            contradictions = contradictions,
            pendingKeepKeys = outcomes.asSequence()
                .filter { (_, outcome) -> outcome == DeviceShadowFilterOutcome.PENDING_KEEP }
                .mapTo(linkedSetOf()) { (key, _) -> key },
            quarantined = membershipPlan is AutoSyncMembershipPlan.Quarantine,
        )
    }
}
