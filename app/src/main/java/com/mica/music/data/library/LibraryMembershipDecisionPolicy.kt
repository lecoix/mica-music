package com.mica.music.data.library

import com.mica.music.data.Song
import com.mica.music.data.scanner.DiscoveryPartitions
import com.mica.music.data.scanner.LibraryEligibility
import com.mica.music.data.scanner.PresenceInventory
import kotlin.math.ceil
import kotlin.math.max

internal sealed interface MembershipDecision {
    data object Keep : MembershipDecision
    data class Change(val change: MembershipChange) : MembershipDecision
}

internal enum class DeviceMembershipKeepReason {
    ELIGIBLE,
    PENDING,
    UNCERTAIN,
}

internal sealed interface DeviceMembershipClassification {
    data class Keep(
        val reason: DeviceMembershipKeepReason,
    ) : DeviceMembershipClassification

    data class Remove(
        val change: MembershipChange,
    ) : DeviceMembershipClassification
}

internal object LibraryMembershipDecisionPolicy {

    fun classifyDeviceObject(
        stableObjectKey: String,
        songId: String?,
        sourceIdentity: SourceIdentityKey,
        presence: PresenceInventory,
        evidenceRevision: String,
    ): MembershipDecision =
        when (
            val detailed = classifyDeviceObjectDetailed(
                stableObjectKey = stableObjectKey,
                songId = songId,
                sourceIdentity = sourceIdentity,
                presence = presence,
                evidenceRevision = evidenceRevision,
            )
        ) {
            is DeviceMembershipClassification.Keep -> MembershipDecision.Keep
            is DeviceMembershipClassification.Remove -> MembershipDecision.Change(detailed.change)
        }

    fun classifyDeviceObjectDetailed(
        stableObjectKey: String,
        songId: String?,
        sourceIdentity: SourceIdentityKey,
        presence: PresenceInventory,
        evidenceRevision: String,
    ): DeviceMembershipClassification {
        val audio = presence.entry(stableObjectKey, DiscoveryPartitions.MEDIASTORE_AUDIO)
        val files = presence.entry(stableObjectKey, DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK)
        val observed = listOfNotNull(audio, files)

        if (observed.any { it.eligibility == LibraryEligibility.ELIGIBLE }) {
            return DeviceMembershipClassification.Keep(DeviceMembershipKeepReason.ELIGIBLE)
        }
        if (observed.any { it.eligibility == LibraryEligibility.PENDING }) {
            return DeviceMembershipClassification.Keep(DeviceMembershipKeepReason.PENDING)
        }
        if (observed.any { it.eligibility == LibraryEligibility.TRASHED }) {
            return DeviceMembershipClassification.Remove(
                MembershipChange(
                    stableObjectKey = stableObjectKey,
                    songId = songId,
                    reason = MembershipRemovalReason.TRASHED,
                    evidenceRevision = evidenceRevision,
                    sourceIdentity = sourceIdentity,
                ),
            )
        }
        if (observed.any { it.eligibility == LibraryEligibility.FILTERED_OUT }) {
            return DeviceMembershipClassification.Remove(
                MembershipChange(
                    stableObjectKey = stableObjectKey,
                    songId = songId,
                    reason = MembershipRemovalReason.FILTERED_OUT,
                    evidenceRevision = evidenceRevision,
                    sourceIdentity = sourceIdentity,
                ),
            )
        }

        val authoritativeAbsence =
            presence.absenceConfirmed(
                stableObjectKey,
                DiscoveryPartitions.MEDIASTORE_AUDIO,
            ) &&
                presence.absenceConfirmed(
                    stableObjectKey,
                    DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK,
                )
        if (!authoritativeAbsence) {
            return DeviceMembershipClassification.Keep(DeviceMembershipKeepReason.UNCERTAIN)
        }

        return DeviceMembershipClassification.Remove(
            MembershipChange(
                stableObjectKey = stableObjectKey,
                songId = songId,
                reason = MembershipRemovalReason.CONFIRMED_MISSING,
                evidenceRevision = evidenceRevision,
                sourceIdentity = sourceIdentity,
            ),
        )
    }
}

internal sealed interface AutoSyncMembershipPlan {
    data class Apply(
        val membershipChanges: List<MembershipChange>,
    ) : AutoSyncMembershipPlan

    data class Quarantine(
        val membershipChanges: List<MembershipChange>,
        val reason: MassDeletionQuarantineReason,
    ) : AutoSyncMembershipPlan
}

internal object AutoSyncMembershipPlanner {
    fun planSaf(
        previousSongs: List<Song>,
        sourceIdentity: SourceIdentityKey,
        observedStableObjectKeys: Set<String>,
        discoveryComplete: Boolean,
        absenceEvidenceRevision: String,
        independentlyVerifiedMissingKeys: Set<String> = emptySet(),
    ): AutoSyncMembershipPlan {
        require(sourceIdentity.source == com.mica.music.data.ScanSource.FOLDER)
        if (!discoveryComplete) return AutoSyncMembershipPlan.Apply(emptyList())

        val changes = previousSongs.asSequence()
            .filter { it.id !in observedStableObjectKeys }
            .map { song ->
                MembershipChange(
                    stableObjectKey = song.id,
                    songId = song.id,
                    reason = MembershipRemovalReason.CONFIRMED_MISSING,
                    evidenceRevision = absenceEvidenceRevision,
                    sourceIdentity = sourceIdentity,
                )
            }
            .toList()

        return when (
            val guard = MassDeletionGuard.evaluateObservedKeys(
                previousLibraryCount = previousSongs.size,
                observedStableObjectKeys = observedStableObjectKeys,
                membershipChanges = changes,
                independentlyVerifiedMissingKeys = independentlyVerifiedMissingKeys,
            )
        ) {
            MassDeletionDecision.Allow -> AutoSyncMembershipPlan.Apply(changes)
            is MassDeletionDecision.Quarantine -> AutoSyncMembershipPlan.Quarantine(
                membershipChanges = changes,
                reason = guard.reason,
            )
        }
    }

    fun planDevice(
        previousSongs: List<Song>,
        sourceIdentity: SourceIdentityKey,
        presence: PresenceInventory,
        absenceEvidenceRevision: String,
        independentlyVerifiedMissingKeys: Set<String> = emptySet(),
    ): AutoSyncMembershipPlan {
        require(sourceIdentity.source == com.mica.music.data.ScanSource.DEVICE)

        val changes = previousSongs.mapNotNull { song ->
            when (
                val decision = LibraryMembershipDecisionPolicy.classifyDeviceObject(
                    stableObjectKey = userExclusionStableObjectKey(song),
                    songId = song.id,
                    sourceIdentity = sourceIdentity,
                    presence = presence,
                    evidenceRevision = absenceEvidenceRevision,
                )
            ) {
                MembershipDecision.Keep -> null
                is MembershipDecision.Change -> decision.change
            }
        }
        return when (
            val guard = MassDeletionGuard.evaluate(
                previousLibraryCount = previousSongs.size,
                presence = presence,
                membershipChanges = changes,
                independentlyVerifiedMissingKeys = independentlyVerifiedMissingKeys,
            )
        ) {
            MassDeletionDecision.Allow -> AutoSyncMembershipPlan.Apply(changes)
            is MassDeletionDecision.Quarantine -> AutoSyncMembershipPlan.Quarantine(
                membershipChanges = changes,
                reason = guard.reason,
            )
        }
    }
}

internal enum class MassDeletionQuarantineReason {
    INVENTORY_COLLAPSE,
    LARGE_UNVERIFIED_BATCH,
}

internal sealed interface MassDeletionDecision {
    data object Allow : MassDeletionDecision
    data class Quarantine(val reason: MassDeletionQuarantineReason) : MassDeletionDecision
}

internal object MassDeletionGuard {
    internal const val MIN_LARGE_BATCH_COUNT = 50
    internal const val LARGE_BATCH_RATIO = 0.25

    fun evaluate(
        previousLibraryCount: Int,
        presence: PresenceInventory,
        membershipChanges: List<MembershipChange>,
        independentlyVerifiedMissingKeys: Set<String> = emptySet(),
    ): MassDeletionDecision {
        val observedKeys = presence.entries.values
            .mapTo(linkedSetOf()) { it.stableObjectKey }
        return evaluateObservedKeys(
            previousLibraryCount = previousLibraryCount,
            observedStableObjectKeys = observedKeys,
            membershipChanges = membershipChanges,
            independentlyVerifiedMissingKeys = independentlyVerifiedMissingKeys,
        )
    }

    fun evaluateObservedKeys(
        previousLibraryCount: Int,
        observedStableObjectKeys: Set<String>,
        membershipChanges: List<MembershipChange>,
        independentlyVerifiedMissingKeys: Set<String> = emptySet(),
    ): MassDeletionDecision {
        val missing = membershipChanges.filter {
            it.reason == MembershipRemovalReason.CONFIRMED_MISSING
        }
        if (missing.isEmpty()) return MassDeletionDecision.Allow
        val allMissingIndependentlyVerified = missing.all {
            it.stableObjectKey in independentlyVerifiedMissingKeys
        }

        if (
            previousLibraryCount > 0 &&
            observedStableObjectKeys.isEmpty() &&
            !allMissingIndependentlyVerified
        ) {
            return MassDeletionDecision.Quarantine(
                MassDeletionQuarantineReason.INVENTORY_COLLAPSE,
            )
        }

        val ratioThreshold = ceil(previousLibraryCount * LARGE_BATCH_RATIO).toInt()
        val largeBatchThreshold = max(MIN_LARGE_BATCH_COUNT, ratioThreshold)
        if (missing.size >= largeBatchThreshold && !allMissingIndependentlyVerified) {
            return MassDeletionDecision.Quarantine(
                MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            )
        }
        return MassDeletionDecision.Allow
    }
}
