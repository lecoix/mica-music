package com.mica.music.data.library

import com.mica.music.data.ScanSource
import com.mica.music.data.ScannedSongLyrics
import com.mica.music.data.Song
import com.mica.music.data.scanner.AutoSyncVisibleDelta
import com.mica.music.data.scanner.DiscoveryPartitions
import com.mica.music.data.scanner.SafFastVerifyPlan
import com.mica.music.data.scanner.SafTreeMetadataSnapshot

/**
 * Pure S4 readiness seam from validated SAF shadow output to an authority-commit proposal.
 *
 * This planner never writes Room/memory and deliberately allows only already post-validated object
 * results into [nextSnapshot]. Destructive membership is additionally gated by the shared mass
 * deletion guard. Retry/UNKNOWN compensation is merged into the same state mutation that will be
 * supplied to [LibraryScanOrchestrator.publishAutoSyncSnapshot].
 */
internal data class SafAutoSyncPublicationPlan(
    val nextSnapshot: List<Song>,
    val visibleDelta: AutoSyncVisibleDelta,
    val membershipChanges: List<MembershipChange>,
    val autoSyncStateMutation: LibraryAutoSyncStateMutation,
    val lyricsToStage: List<ScannedSongLyrics>,
    val checkpointIncluded: Boolean,
    val quarantineReason: MassDeletionQuarantineReason? = null,
) {
    val hasAuthorityMutation: Boolean
        get() = visibleDelta.hasVisibleChanges ||
            autoSyncStateMutation.checkpoints.isNotEmpty() ||
            autoSyncStateMutation.retryUpserts.isNotEmpty() ||
            autoSyncStateMutation.retryDeleteKeys.isNotEmpty() ||
            lyricsToStage.isNotEmpty()
}

internal object SafAutoSyncPublicationPlanner {
    internal const val SAF_CHECKPOINT_PROVIDER_VERSION = "saf-full-walk-v1"
    internal const val SAF_CHECKPOINT_GENERATION = 0L

    fun plan(
        sourceIdentity: SourceIdentityKey,
        activationEpoch: Long,
        configFingerprint: String,
        nowMs: Long,
        currentSongs: List<Song>,
        snapshot: SafTreeMetadataSnapshot,
        verifyPlan: SafFastVerifyPlan,
        probePlan: SafAutoProbePlan,
        validation: SafShadowPostValidationResult,
        relationValidation: SafShadowRelationRematchResult,
        retryPlan: SafShadowRetryPlan,
        unknownDebtPlan: SafUnknownFingerprintDebtPlan,
        excludedStableObjectKeys: Set<String> = emptySet(),
    ): SafAutoSyncPublicationPlan {
        require(sourceIdentity.source == ScanSource.FOLDER)

        val discoveryComplete =
            snapshot.discoveryReport.isComplete(DiscoveryPartitions.SAF_TREE)
        val membershipEvidenceRevision =
            "saf-tree-observation:$activationEpoch:$nowMs:${snapshot.entries.size}"
        val membershipPlan = AutoSyncMembershipPlanner.planSaf(
            previousSongs = currentSongs,
            sourceIdentity = sourceIdentity,
            observedStableObjectKeys = snapshot.entries
                .asSequence()
                .mapTo(linkedSetOf()) { it.stableObjectKey },
            discoveryComplete = discoveryComplete,
            absenceEvidenceRevision = membershipEvidenceRevision,
        )
        val membershipChanges = when (membershipPlan) {
            is AutoSyncMembershipPlan.Apply -> membershipPlan.membershipChanges
            is AutoSyncMembershipPlan.Quarantine -> emptyList()
        }
        val quarantineMembershipChanges =
            (membershipPlan as? AutoSyncMembershipPlan.Quarantine)?.membershipChanges.orEmpty()
        val quarantineReason =
            (membershipPlan as? AutoSyncMembershipPlan.Quarantine)?.reason
        val approvedRemovedKeys = membershipChanges
            .mapTo(linkedSetOf(), MembershipChange::stableObjectKey)
        val quarantinedRemovedKeys = quarantineMembershipChanges
            .mapTo(linkedSetOf(), MembershipChange::stableObjectKey)
        if (discoveryComplete) {
            require(verifyPlan.removedStableObjectKeys == approvedRemovedKeys + quarantinedRemovedKeys) {
                "SAF verify removal set and membership evidence diverged"
            }
        }

        val currentById = currentSongs.associateBy(Song::id)
        val nextById = LinkedHashMap<String, Song>(currentById)
        approvedRemovedKeys.forEach(nextById::remove)

        // If relation work for an audio object is unresolved, hold the whole object update. This
        // avoids publishing fresh tags/fingerprint together with a stale video/MV relation.
        val relationBlockedKeys = relationValidation.unresolvedStableObjectKeys
        validation.resolvedSongsByStableObjectKey.forEach { (stableKey, song) ->
            if (stableKey in approvedRemovedKeys) return@forEach
            if (stableKey in relationBlockedKeys) return@forEach
            if (stableKey in excludedStableObjectKeys) return@forEach
            nextById[stableKey] = song
        }
        // A relation rematch can update otherwise-unchanged songs in an affected directory. Its
        // result was already post-observation validated and therefore may replace the projected row.
        relationValidation.resolvedSongsByStableObjectKey.forEach { (stableKey, song) ->
            if (stableKey in approvedRemovedKeys) return@forEach
            if (stableKey in excludedStableObjectKeys) return@forEach
            nextById[stableKey] = song
        }

        val addedIds = nextById.keys
            .filterTo(linkedSetOf()) { it !in currentById }
        val updatedIds = nextById.keys
            .asSequence()
            .filter { it in currentById }
            .filter { nextById.getValue(it) != currentById.getValue(it) }
            .toCollection(linkedSetOf())
        val visibleDelta = AutoSyncVisibleDelta(
            addedIds = addedIds,
            updatedIds = updatedIds,
            removedStableObjectKeys = approvedRemovedKeys,
        )
        val visibleChangedKeys = addedIds + updatedIds
        val lyricsToStage = validation.resolvedLyricsByStableObjectKey
            .asSequence()
            .filter { (stableKey, _) ->
                stableKey in visibleChangedKeys &&
                    stableKey !in relationBlockedKeys &&
                    stableKey !in excludedStableObjectKeys
            }
            .map { it.value }
            .sortedBy(ScannedSongLyrics::songId)
            .toList()

        val retryUpserts = (retryPlan.retryUpserts + unknownDebtPlan.retryUpserts)
            .associateBy(LibraryRetryItem::retryKey)
            .values
            .sortedBy(LibraryRetryItem::retryKey)
        val retryUpsertKeys = retryUpserts.mapTo(hashSetOf(), LibraryRetryItem::retryKey)
        val quarantinedRetryKeys = buildSet {
            quarantinedRemovedKeys.forEach { stableKey ->
                add(SafShadowRetryPlanner.retryKey(stableKey))
                add(SafUnknownFingerprintDebtPlanner.retryKey(stableKey))
            }
        }
        val retryDeleteKeys = (retryPlan.retryDeleteKeys + unknownDebtPlan.retryDeleteKeys)
            .filterNotTo(sortedSetOf()) {
                it in retryUpsertKeys || it in quarantinedRetryKeys
            }

        val checkpointIncluded = checkpointEligible(
            discoveryComplete = discoveryComplete,
            quarantineReason = quarantineReason,
            probePlan = probePlan,
            validation = validation,
            relationValidation = relationValidation,
        )
        val checkpoints = if (checkpointIncluded) {
            listOf(
                LibrarySyncCheckpoint(
                    sourceIdentity = sourceIdentity,
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    providerVersion = SAF_CHECKPOINT_PROVIDER_VERSION,
                    generation = SAF_CHECKPOINT_GENERATION,
                    configFingerprint = configFingerprint,
                    lastSuccessfulAutoSyncAtMs = nowMs,
                ),
            )
        } else {
            emptyList()
        }

        return SafAutoSyncPublicationPlan(
            nextSnapshot = nextById.values.toList(),
            visibleDelta = visibleDelta,
            membershipChanges = membershipChanges,
            autoSyncStateMutation = LibraryAutoSyncStateMutation(
                sourceIdentity = sourceIdentity,
                checkpoints = checkpoints,
                retryUpserts = retryUpserts,
                retryDeleteKeys = retryDeleteKeys,
            ),
            lyricsToStage = lyricsToStage,
            checkpointIncluded = checkpointIncluded,
            quarantineReason = quarantineReason,
        )
    }

    private fun checkpointEligible(
        discoveryComplete: Boolean,
        quarantineReason: MassDeletionQuarantineReason?,
        probePlan: SafAutoProbePlan,
        validation: SafShadowPostValidationResult,
        relationValidation: SafShadowRelationRematchResult,
    ): Boolean {
        if (!discoveryComplete) return false
        if (quarantineReason != null) return false
        if (probePlan.deferred.isNotEmpty()) return false
        if (probePlan.budgetDeferred.isNotEmpty()) return false
        if (validation.issues.isNotEmpty()) return false
        if (relationValidation.issues.isNotEmpty()) return false
        if (relationValidation.unresolvedStableObjectKeys.isNotEmpty()) return false
        return true
    }
}
