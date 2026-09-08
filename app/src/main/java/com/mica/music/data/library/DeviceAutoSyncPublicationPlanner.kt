package com.mica.music.data.library

import com.mica.music.data.ScanSource
import com.mica.music.data.ScannedSongLyrics
import com.mica.music.data.Song
import com.mica.music.data.scanner.AutoSyncVisibleDelta
import com.mica.music.data.scanner.DeviceGenerationSnapshot

/**
 * Pure DEVICE publication proposal built only from post-validated object results.
 *
 * A generation checkpoint may advance past retryable object failures only when the corresponding
 * retry debt is represented in the same atomic state mutation. Playback defers, unresolved
 * re-query work, non-ledger-safe failures, and mass-deletion quarantine keep the cursor anchored.
 */
internal data class DeviceAutoSyncPublicationPlan(
    val nextSnapshot: List<Song>,
    val visibleDelta: AutoSyncVisibleDelta,
    val membershipChanges: List<MembershipChange>,
    val autoSyncStateMutation: LibraryAutoSyncStateMutation,
    val fullLyricsToStage: List<ScannedSongLyrics>,
    val externalLyricsToStage: List<ScannedSongLyrics>,
    val checkpointIncluded: Boolean,
    val quarantineReason: MassDeletionQuarantineReason? = null,
) {
    val hasAuthorityMutation: Boolean
        get() = visibleDelta.hasVisibleChanges ||
            autoSyncStateMutation.checkpoints.isNotEmpty() ||
            autoSyncStateMutation.checkpointDeleteKeys.isNotEmpty() ||
            autoSyncStateMutation.retryUpserts.isNotEmpty() ||
            autoSyncStateMutation.retryDeleteKeys.isNotEmpty() ||
            fullLyricsToStage.isNotEmpty() ||
            externalLyricsToStage.isNotEmpty()
}

internal object DeviceAutoSyncPublicationPlanner {
    fun plan(
        sourceIdentity: SourceIdentityKey,
        configFingerprint: String,
        nowMs: Long,
        currentSongs: List<Song>,
        advanceTo: DeviceGenerationSnapshot.Available,
        existingCheckpoints: List<LibrarySyncCheckpoint>,
        membershipPlan: AutoSyncMembershipPlan,
        probePlan: DeviceAutoProbePlan,
        execution: DeviceShadowProbeExecutionResult,
        retryPlan: DeviceShadowRetryPlan,
        excludedStableObjectKeys: Set<String> = emptySet(),
    ): DeviceAutoSyncPublicationPlan {
        require(sourceIdentity.source == ScanSource.DEVICE)

        val quarantineReason = (membershipPlan as? AutoSyncMembershipPlan.Quarantine)?.reason
        val membershipChanges = when (membershipPlan) {
            is AutoSyncMembershipPlan.Apply -> membershipPlan.membershipChanges
            is AutoSyncMembershipPlan.Quarantine -> emptyList()
        }
        val approvedRemovedKeys = membershipChanges
            .mapTo(linkedSetOf(), MembershipChange::stableObjectKey)

        val currentById = currentSongs.associateBy(Song::id)
        val nextById = LinkedHashMap<String, Song>(currentById)
        approvedRemovedKeys.forEach(nextById::remove)
        execution.resolvedSongsByStableObjectKey.forEach { (stableKey, song) ->
            if (stableKey in approvedRemovedKeys) return@forEach
            if (stableKey in excludedStableObjectKeys) return@forEach
            nextById[stableKey] = song.copy(lyricsLoaded = false)
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
        val eligibleLyrics = execution.resolvedLyricsByStableObjectKey
            .asSequence()
            .filter { (stableKey, _) ->
                stableKey in visibleChangedKeys &&
                    stableKey !in approvedRemovedKeys &&
                    stableKey !in excludedStableObjectKeys
            }
            .associate { it.key to it.value }
        val fullLyricsKeys = execution.fullReplaceLyricsStableObjectKeys
        val fullLyricsToStage = eligibleLyrics.asSequence()
            .filter { (stableKey, _) -> stableKey in fullLyricsKeys }
            .map { it.value }
            .sortedBy(ScannedSongLyrics::songId)
            .toList()
        val externalLyricsToStage = eligibleLyrics.asSequence()
            .filterNot { (stableKey, _) -> stableKey in fullLyricsKeys }
            .map { it.value }
            .sortedBy(ScannedSongLyrics::songId)
            .toList()

        val accountedReadyKeys = buildSet {
            addAll(execution.resolvedSongsByStableObjectKey.keys)
            execution.issues.mapTo(this, DeviceShadowProbeIssue::stableObjectKey)
        }
        val unaccountedReadyKeys = probePlan.ready
            .asSequence()
            .map(DeviceAutoProbeObjectPlan::stableObjectKey)
            .filterNot(accountedReadyKeys::contains)
            .toSet()

        val checkpointIncluded =
            quarantineReason == null &&
                probePlan.deferred.isEmpty() &&
                probePlan.requeryRequired.isEmpty() &&
                retryPlan.ignoredIssueCount == 0 &&
                unaccountedReadyKeys.isEmpty()

        val checkpointMutation = if (checkpointIncluded) {
            DeviceGenerationCheckpointCodec.replacementMutation(
                sourceIdentity = sourceIdentity,
                snapshot = advanceTo,
                configFingerprint = configFingerprint,
                committedAtMs = nowMs,
                existingCheckpoints = existingCheckpoints,
            )
        } else {
            LibraryAutoSyncStateMutation(sourceIdentity = sourceIdentity)
        }

        val retryUpserts = retryPlan.retryUpserts
            .associateBy(LibraryRetryItem::retryKey)
            .values
            .sortedBy(LibraryRetryItem::retryKey)
        val retryUpsertKeys = retryUpserts.mapTo(hashSetOf(), LibraryRetryItem::retryKey)
        val retryDeleteKeys = retryPlan.retryDeleteKeys
            .filterNotTo(sortedSetOf()) { it in retryUpsertKeys }

        return DeviceAutoSyncPublicationPlan(
            nextSnapshot = nextById.values.toList(),
            visibleDelta = visibleDelta,
            membershipChanges = membershipChanges,
            autoSyncStateMutation = LibraryAutoSyncStateMutation(
                sourceIdentity = sourceIdentity,
                checkpoints = checkpointMutation.checkpoints,
                checkpointDeleteKeys = checkpointMutation.checkpointDeleteKeys,
                retryUpserts = retryUpserts,
                retryDeleteKeys = retryDeleteKeys,
            ),
            fullLyricsToStage = fullLyricsToStage,
            externalLyricsToStage = externalLyricsToStage,
            checkpointIncluded = checkpointIncluded,
            quarantineReason = quarantineReason,
        )
    }
}
