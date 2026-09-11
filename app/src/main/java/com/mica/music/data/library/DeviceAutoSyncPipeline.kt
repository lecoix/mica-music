package com.mica.music.data.library

import com.mica.music.data.ScanSource
import com.mica.music.data.Song
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.data.scanner.*
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.withContext

/**
 * DEVICE-specific AUTO discovery/probe/retry pipeline.
 *
 * Scheduler follow-ups are returned as [AutoSyncPostCommit] actions; this pipeline does not own
 * scheduler mutation.
 */
internal class DeviceAutoSyncPipeline(
    private val backing: MusicLibraryBacking,
    private val publicationAuthority: AutoSyncPublicationAuthority,
    private val canonicalCoverage: DeviceShadowCanonicalCoverageTracker,
    private val canonicalProjection: DeviceShadowCanonicalProjectionTracker,
) {
    private data class DeviceShadowDeltaAnalysis(
        val scanStartSnapshot: List<Song>,
        val candidates: DeviceDeltaCandidatePlan,
        val lyricsDiff: DeviceLyricsSidecarDiff,
        val membershipPlan: AutoSyncMembershipPlan,
        val membershipAudit: DeviceShadowMembershipAudit,
        val folderCasingPlan: DeviceDeltaFolderCasingPlan,
        val probePlan: DeviceAutoProbePlan,
        val probeExecution: DeviceShadowProbeExecutionResult,
        val retryItems: List<LibraryRetryItem>,
        val retryPlan: DeviceShadowRetryPlan,
        val retryObservation: DeviceRetryObservationResult,
        val excludedStableObjectKeys: Set<String>,
        val publicationPlan: DeviceAutoSyncPublicationPlan,
    )

    suspend fun execute(
        operation: ScheduledLibraryOperation,
        token: LibraryOperationToken,
        publishAuthority: Boolean,
    ): AutoSyncPostCommit {
        val postCommit = AutoSyncPostCommitCollector()
        executeInternal(
            operation = operation,
            token = token,
            publishAuthority = publishAuthority,
            postCommit = postCommit,
        )
        return postCommit.build()
    }

    private suspend fun executeInternal(
        operation: ScheduledLibraryOperation,
        token: LibraryOperationToken,
        publishAuthority: Boolean,
        postCommit: AutoSyncPostCommitCollector,
    ) {
        val before = backing.captureShadowObservationStamp(ScanSource.DEVICE) ?: return
        if (!before.matchesOperationToken(token)) return
        val configKey = deviceShadowConfigKey(
            configFingerprint = before.configFingerprint,
            activationEpoch = before.sourceActivation.activationEpoch,
        )
        val persistedCheckpoints = withContext(backing.ioDispatcher) {
            backing.libraryStore.loadSyncCheckpoints(before.sourceActivation.sourceIdentity)
        }
        if (backing.captureShadowObservationStamp(ScanSource.DEVICE) != before) return
        DeviceGenerationCheckpointCodec.restoreSnapshot(
            sourceIdentity = before.sourceActivation.sourceIdentity,
            configFingerprint = before.configFingerprint,
            checkpoints = persistedCheckpoints,
        )?.let { persistedAnchor ->
            val restored = backing.deviceAutoSyncShadow.restorePersistedAnchor(
                snapshot = persistedAnchor,
                configKey = configKey,
            )
            if (restored) {
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "device durable-anchor restored request=${operation.requestSequence} " +
                        "volumes=${persistedAnchor.volumes.mapValues { it.value.generation }}",
                )
            }
        }
        val observation = withContext(backing.ioDispatcher) {
            backing.deviceAutoSyncShadow.observe(configKey)
        }
        val after = backing.captureShadowObservationStamp(ScanSource.DEVICE)
        if (after != before) {
            DiagnosticLog.event(
                "LibraryAutoSync",
                "device shadow stale-drop request=${operation.requestSequence} " +
                    "dirty=${operation.dirtySequenceAtStart}",
            )
            return
        }

        val deltaAnalysis = if (observation is DeviceAutoSyncShadowObservation.DeltaCandidate) {
            val transientByAuthority = observation.batch.rows.asSequence()
                .filter {
                    it.eligibilityAuthority !=
                        com.mica.music.data.scanner.DeviceEligibilityAuthority.AUTHORITATIVE
                }
                .groupingBy { it.eligibilityAuthority }
                .eachCount()
            if (transientByAuthority.isNotEmpty()) {
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "device shadow cursor-held request=${operation.requestSequence} " +
                        "reason=provider-state-transient objects=" +
                        "${transientByAuthority.values.sum()} authorities=$transientByAuthority",
                )
                return
            }
            val candidates = DeviceDeltaCandidatePlanner.plan(
                batch = observation.batch,
                currentSongs = backing.songs,
            )
            val lyricsDiff = DeviceLyricsSidecarDiffPlanner.plan(
                currentSongs = backing.songs,
                inventory = observation.lyricsSidecarInventory,
            )
            if (candidates.hasContradictions || !lyricsDiff.safeToAdvance) {
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "device shadow candidate-reject request=${operation.requestSequence} " +
                        "contradictions=${candidates.contradictions.size} " +
                        "unverifiableLyrics=${lyricsDiff.unverifiableSongIds.size}",
                )
                return
            }
            val folderCasingPlan = withContext(backing.ioDispatcher) {
                DeviceDeltaFolderCasingPlanner.plan(
                    candidates = candidates,
                    currentSongs = backing.songs,
                    identityResolver = DeviceFolderIdentityResolver { probe ->
                        resolveMediaStoreDirectoryIdentity(
                            context = backing.context,
                            mediaUri = probe.mediaUri,
                            filePath = probe.filePath,
                            folderPath = probe.folderPath,
                        )
                    },
                )
            }
            val mediaStoreCapabilities =
                observation.presenceInventory.deviceMediaStoreCapabilityProfile
            if (
                mediaStoreCapabilities != null &&
                !mediaStoreCapabilities.allChannelsDestructiveAbsenceSafe
            ) {
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "device shadow candidate-reject request=${operation.requestSequence} " +
                        "reason=presence-capability " +
                        "safeChannels=" +
                        "${mediaStoreCapabilities.channels.values.count { it.destructiveAbsenceSafe }}/" +
                        "${mediaStoreCapabilities.channels.size}",
                )
                return
            }
            val membershipEvidenceRevision = observation.advanceTo.volumes
                .toSortedMap()
                .entries
                .joinToString(prefix = "device-generation:", separator = ";") { (name, state) ->
                    "$name:${state.providerVersion}:${state.generation}"
                }
            val membershipPlan = AutoSyncMembershipPlanner.planDevice(
                previousSongs = backing.songs,
                sourceIdentity = before.sourceActivation.sourceIdentity,
                presence = observation.presenceInventory,
                absenceEvidenceRevision = membershipEvidenceRevision,
            )
            val membershipAudit = DeviceShadowMembershipAuditor.audit(
                previousSongs = backing.songs,
                candidates = candidates,
                presence = observation.presenceInventory,
                membershipPlan = membershipPlan,
                sourceIdentity = before.sourceActivation.sourceIdentity,
                evidenceRevision = membershipEvidenceRevision,
            )
            if (!membershipAudit.fullyConsistent) {
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "device shadow candidate-reject request=${operation.requestSequence} " +
                        "reason=membership-audit contradictions=" +
                        "${membershipAudit.contradictions.size}",
                )
                return
            }
            val currentSongs = backing.songs.toList()
            val nowMs = backing.scanEnvironment.currentTimeMillis()
            val scanOptions = LibraryScanSettings.scanOptions(backing.context)
            val retryItems = withContext(backing.ioDispatcher) {
                backing.libraryStore.loadDueRetryItems(
                    sourceIdentity = before.sourceActivation.sourceIdentity,
                    retryKind = LibraryRetryKind.OBJECT_PROBE,
                    activationEpoch = before.sourceActivation.activationEpoch,
                    nowMs = nowMs,
                    limit = LibraryRetryPaging.DUE_WORK_BUDGET,
                )
            }
            val excludedStableObjectKeys = withContext(backing.ioDispatcher) {
                backing.libraryStore.loadUserExclusions(before.sourceActivation.sourceIdentity)
                    .mapTo(linkedSetOf(), LibraryUserExclusion::stableObjectKey)
            }
            val appliedRemovedKeys = when (membershipPlan) {
                is AutoSyncMembershipPlan.Apply ->
                    membershipPlan.membershipChanges
                        .mapTo(linkedSetOf(), MembershipChange::stableObjectKey)
                is AutoSyncMembershipPlan.Quarantine -> emptySet()
            }
            val retryItemsForProbe = retryItems.filterNot { retry ->
                retry.stableObjectKey in appliedRemovedKeys ||
                    retry.stableObjectKey in excludedStableObjectKeys
            }
            val retryObservation = if (
                retryItemsForProbe.any {
                    it.retryKind == LibraryRetryKind.OBJECT_PROBE &&
                        it.nextRetryAtMs <= nowMs
                }
            ) {
                withContext(backing.ioDispatcher) {
                    backing.deviceRetryObservationRuntime.resolve(
                        DeviceRetryObservationRequest(
                            currentSongs = currentSongs,
                            retryItems = retryItemsForProbe,
                            nowMs = nowMs,
                            scanOptions = scanOptions,
                        ),
                    )
                }
            } else {
                DeviceRetryObservationResult(
                    observedRowsByStableObjectKey = emptyMap(),
                    missingStableObjectKeys = emptySet(),
                    unavailableStableObjectKeys = emptySet(),
                )
            }
            val probePlan = DeviceAutoProbePlanner.plan(
                candidates = candidates,
                currentSongs = currentSongs,
                retryItems = retryItemsForProbe,
                sourceIdentity = before.sourceActivation.sourceIdentity,
                activationEpoch = before.sourceActivation.activationEpoch,
                nowMs = nowMs,
                playback = backing.playbackIoSnapshot(),
                retryObservationRowsByStableObjectKey =
                    retryObservation.observedRowsByStableObjectKey,
                excludedStableObjectKeys = excludedStableObjectKeys,
            )
            val probeExecution = withContext(backing.ioDispatcher) {
                backing.deviceShadowProbeRuntime.execute(
                    DeviceShadowProbeRequest(
                        probePlan = probePlan,
                        scanOptions = scanOptions,
                        lyricsInventory = observation.lyricsSidecarInventory,
                        currentSongs = currentSongs,
                        currentSourceIdentity = before.sourceActivation.sourceIdentity,
                        currentActivationEpoch = before.sourceActivation.activationEpoch,
                        folderCasingPlan = folderCasingPlan,
                        playbackSnapshotProvider = backing::playbackIoSnapshot,
                    ),
                )
            }
            val retryableIssueStableObjectKeys = probeExecution.issues.asSequence()
                .filter { it.kind != DeviceShadowProbeIssueKind.PLAYBACK_DEFERRED }
                .map(DeviceShadowProbeIssue::stableObjectKey)
                .distinct()
                .take(LibraryRetryPaging.DUE_WORK_BUDGET)
                .toList()
            val retryOutcomeItems = if (retryableIssueStableObjectKeys.isEmpty()) {
                emptyList()
            } else {
                withContext(backing.ioDispatcher) {
                    backing.libraryStore.loadRetryItemsForStableObjectKeys(
                        before.sourceActivation.sourceIdentity,
                        retryableIssueStableObjectKeys,
                    )
                }
            }
            val retryItemsForMutation = linkedMapOf<String, LibraryRetryItem>().apply {
                retryItems.forEach { put(it.retryKey, it) }
                retryOutcomeItems.forEach { put(it.retryKey, it) }
            }.values.toList()
            val retryPlan = DeviceShadowRetryPlanner.plan(
                sourceIdentity = before.sourceActivation.sourceIdentity,
                activationEpoch = before.sourceActivation.activationEpoch,
                nowMs = nowMs,
                probePlan = probePlan,
                existingRetryItems = retryItemsForMutation,
                execution = probeExecution,
                authoritativeRemovedStableObjectKeys =
                    appliedRemovedKeys + excludedStableObjectKeys,
                retryObservationMissingStableObjectKeys =
                    retryObservation.missingStableObjectKeys,
                retryObservationUnavailableStableObjectKeys =
                    retryObservation.unavailableStableObjectKeys,
            )
            val publicationPlan = DeviceAutoSyncPublicationPlanner.plan(
                minDurationMs = LibraryScanSettings.scanOptions(backing.context).minDurationMs,
                sourceIdentity = before.sourceActivation.sourceIdentity,
                configFingerprint = before.configFingerprint,
                nowMs = nowMs,
                currentSongs = currentSongs,
                advanceTo = observation.advanceTo,
                existingCheckpoints = persistedCheckpoints,
                membershipPlan = membershipPlan,
                probePlan = probePlan,
                execution = probeExecution,
                retryPlan = retryPlan,
                excludedStableObjectKeys = excludedStableObjectKeys,
            )
            backing.hasPlaybackDeferredAutoWork =
                probePlan.deferred.isNotEmpty() ||
                    probeExecution.playbackDeferredKeys.isNotEmpty()
            canonicalCoverage.recordDelta(
                candidates = candidates,
                lyricsDiff = lyricsDiff,
                membershipPlan = membershipPlan,
            )
            canonicalProjection.recordDelta(
                candidates = candidates,
                lyricsDiff = lyricsDiff,
                membershipPlan = membershipPlan,
                folderCasingPlan = folderCasingPlan,
                resolvedObjectsByStableObjectKey =
                    probeExecution.resolvedObjectsByStableObjectKey,
                membershipAudit = membershipAudit,
            )
            DeviceShadowDeltaAnalysis(
                scanStartSnapshot = currentSongs,
                candidates = candidates,
                lyricsDiff = lyricsDiff,
                membershipPlan = membershipPlan,
                membershipAudit = membershipAudit,
                folderCasingPlan = folderCasingPlan,
                probePlan = probePlan,
                probeExecution = probeExecution,
                retryItems = retryItemsForMutation,
                retryPlan = retryPlan,
                retryObservation = retryObservation,
                excludedStableObjectKeys = excludedStableObjectKeys,
                publicationPlan = publicationPlan,
            )
        } else {
            null
        }

        val finalStamp = backing.captureShadowObservationStamp(ScanSource.DEVICE)
        if (finalStamp != before) {
            DiagnosticLog.event(
                "LibraryAutoSync",
                "device shadow stale-drop-post-analysis request=${operation.requestSequence}",
            )
            return
        }

        if (
            publishAuthority &&
            observation is DeviceAutoSyncShadowObservation.NoChange &&
            executeDeviceNoChangeRetryAuthority(
                operation = operation,
                token = token,
                before = before,
                observation = observation,
                persistedCheckpoints = persistedCheckpoints,
                postCommit = postCommit,
            )
        ) {
            return
        }

        if (
            publishAuthority &&
            observation is DeviceAutoSyncShadowObservation.DeltaCandidate
        ) {
            val analysis = requireNotNull(deltaAnalysis)
            val publicationPlan = analysis.publicationPlan
            val publicationResult = publicationAuthority.publishDevicePlan(
                token = token,
                scanStartSnapshot = analysis.scanStartSnapshot,
                plan = publicationPlan,
            )
            if (publicationResult == null) {
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "device auto stale-drop-publication request=${operation.requestSequence}",
                )
                return
            }

            val cursorAccepted = if (publicationPlan.checkpointIncluded) {
                backing.withCurrentOperationIfCurrent(token) {
                    backing.deviceAutoSyncShadow.accept(observation, configKey)
                    true
                } == true
            } else {
                false
            }
            val playbackDeferredKeys = buildSet {
                analysis.probePlan.deferred
                    .mapTo(this, DeviceAutoProbeObjectPlan::stableObjectKey)
                addAll(analysis.probeExecution.playbackDeferredKeys)
            }
            val retryNowMs = backing.scanEnvironment.currentTimeMillis()
            val nextRetryDelayMs = nextDeviceRetryDelayFromStore(
                token = token,
                nowMs = retryNowMs,
                playbackDeferredStableObjectKeys = playbackDeferredKeys,
            )
            val retryWakeRequested = requestDeviceRetryWake(
                postCommit = postCommit,
                token = token,
                publishAuthority = true,
                delayMs = nextRetryDelayMs,
            )

            postCommit.requestArtworkHydration(publicationPlan.visibleDelta.addedIds)

            DiagnosticLog.event(
                "LibraryAutoSync",
                "device auto publication request=${operation.requestSequence} " +
                    "added=${publicationPlan.visibleDelta.addedIds.size} " +
                    "updated=${publicationPlan.visibleDelta.updatedIds.size} " +
                    "removed=${publicationPlan.visibleDelta.removedStableObjectKeys.size} " +
                    "fullLyrics=${publicationPlan.fullLyricsToStage.size} " +
                    "externalLyrics=${publicationPlan.externalLyricsToStage.size} " +
                    "retryUpserts=${publicationPlan.autoSyncStateMutation.retryUpserts.size} " +
                    "retryDeletes=${publicationPlan.autoSyncStateMutation.retryDeleteKeys.size} " +
                    "checkpoint=${publicationPlan.checkpointIncluded} " +
                    "cursorAccepted=$cursorAccepted " +
                    "retryDelayMs=${nextRetryDelayMs ?: -1L} " +
                    "retryWakeRequested=$retryWakeRequested " +
                    "quarantine=${publicationPlan.quarantineReason ?: "none"}",
            )
            if (observation.followUpRequired) {
                postCommit.requestDirtyFollowUp(LibraryOperationCause.FOREGROUND_CATCH_UP)
            }
            return
        }

        val holdShadowCursorForIncompleteProbe = deltaAnalysis?.let { analysis ->
            !analysis.publicationPlan.checkpointIncluded ||
                // Shadow mode does not persist retry debt, so even ledger-safe probe failures
                // must keep the in-memory generation cursor anchored.
                analysis.probeExecution.issues.isNotEmpty()
        } == true
        if (holdShadowCursorForIncompleteProbe) {
            val plannedDeferred = deltaAnalysis?.probePlan?.deferred?.size ?: 0
            val requeryRequired = deltaAnalysis?.probePlan?.requeryRequired?.size ?: 0
            val executionIssues = deltaAnalysis?.probeExecution?.issues?.size ?: 0
            val issueKinds = deltaAnalysis?.probeExecution?.issues
                ?.groupingBy(DeviceShadowProbeIssue::kind)
                ?.eachCount()
                .orEmpty()
            val playbackRaceDeferred =
                deltaAnalysis?.probeExecution?.playbackDeferredKeys?.size ?: 0
            val quarantine = deltaAnalysis?.publicationPlan?.quarantineReason
            DiagnosticLog.event(
                "LibraryAutoSync",
                "device shadow cursor-held request=${operation.requestSequence} " +
                    "reason=probe-incomplete plannedDeferred=$plannedDeferred " +
                    "playbackRaceDeferred=$playbackRaceDeferred " +
                    "requery=$requeryRequired issues=$executionIssues issueKinds=$issueKinds " +
                    "quarantine=${quarantine ?: "none"}",
            )
        } else {
            backing.deviceAutoSyncShadow.accept(observation, configKey)
        }
        when (observation) {
            DeviceAutoSyncShadowObservation.Disabled -> Unit
            is DeviceAutoSyncShadowObservation.NoChange ->
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "device shadow no-op request=${operation.requestSequence} " +
                        "volumes=${observation.snapshot.volumes.size}",
                )
            DeviceAutoSyncShadowObservation.LegacyTimestampFallbackRequired ->
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "device shadow legacy-timestamp-fallback-required request=${operation.requestSequence}",
                )
            is DeviceAutoSyncShadowObservation.Unavailable ->
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "device shadow unavailable request=${operation.requestSequence} detail=${observation.detail}",
                )
            is DeviceAutoSyncShadowObservation.BaselineCandidate ->
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "device shadow baseline-missing request=${operation.requestSequence} " +
                        "reason=${observation.reason} requiresFullAnchor=true " +
                        "volumes=${observation.snapshot.volumes.mapValues { it.value.generation }}",
                )
            is DeviceAutoSyncShadowObservation.ReconcileRequired -> {
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "device shadow reconcile-required request=${operation.requestSequence} " +
                        "detail=${observation.detail}",
                )
                if (observation.followUpRequired) {
                    postCommit.requestDirtyFollowUp(LibraryOperationCause.FOREGROUND_CATCH_UP)
                }
            }
            is DeviceAutoSyncShadowObservation.DeltaCandidate -> {
                val batch = observation.batch
                val analysis = requireNotNull(deltaAnalysis)
                val candidates = analysis.candidates
                val lyricsDiff = analysis.lyricsDiff
                val membershipPlan = analysis.membershipPlan
                val membershipAudit = analysis.membershipAudit
                val folderCasingPlan = analysis.folderCasingPlan
                val probePlan = analysis.probePlan
                val membershipChanges = when (membershipPlan) {
                    is AutoSyncMembershipPlan.Apply -> membershipPlan.membershipChanges
                    is AutoSyncMembershipPlan.Quarantine -> membershipPlan.membershipChanges
                }
                val quarantine = (membershipPlan as? AutoSyncMembershipPlan.Quarantine)?.reason
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "device shadow delta request=${operation.requestSequence} " +
                        "audioRows=${batch.rows.count { it.channel == DeviceDeltaChannel.AUDIO }} " +
                        "fileRows=${batch.rows.count { it.channel == DeviceDeltaChannel.FILES_FALLBACK }} " +
                        "sidecarRows=${batch.rows.count { it.channel == DeviceDeltaChannel.LYRICS_SIDECAR }} " +
                        "audioCandidates=${candidates.audioCandidates.size} " +
                        "sidecarCandidates=${candidates.sidecarCandidates.size} " +
                        "lyricsSignatureChanges=${lyricsDiff.changes.size} " +
                        "membershipChanges=${membershipChanges.size} " +
                        "membershipPendingKeep=${membershipAudit.pendingKeepKeys.size} " +
                        "membershipAuditOk=${membershipAudit.fullyConsistent} " +
                        "removalMissing=" +
                        "${membershipAudit.removalReasonCounts[MembershipRemovalReason.CONFIRMED_MISSING] ?: 0} " +
                        "removalFiltered=" +
                        "${membershipAudit.removalReasonCounts[MembershipRemovalReason.FILTERED_OUT] ?: 0} " +
                        "removalTrashed=" +
                        "${membershipAudit.removalReasonCounts[MembershipRemovalReason.TRASHED] ?: 0} " +
                        "folderCasingReconciled=" +
                        "${folderCasingPlan.decisions.count { it.reconciled }} " +
                        "folderCasingUnresolved=${folderCasingPlan.unresolvedCaseCollisions} " +
                        "folderCaseDistinct=${folderCasingPlan.distinctPhysicalCaseCollisions} " +
                        "probeReady=${probePlan.ready.size} " +
                        "probeRequery=${probePlan.requeryRequired.size} " +
                        "probeDeferred=${probePlan.deferred.size} " +
                        "probeExecuted=${analysis.probeExecution.successfulCount} " +
                        "probeIssues=${analysis.probeExecution.issues.size} " +
                        "probePlaybackRaceDeferred=" +
                        "${analysis.probeExecution.playbackDeferredKeys.size} " +
                        "probeParallelism=${probePlan.heavyProbeParallelism} " +
                        "retryItems=${analysis.retryItems.size} " +
                            "retryPlanUpserts=${analysis.retryPlan.retryUpserts.size} " +
                            "retryPlanDeletes=${analysis.retryPlan.retryDeleteKeys.size} " +
                            "publicationCheckpoint=${analysis.publicationPlan.checkpointIncluded} " +
                        "quarantine=${quarantine ?: "none"} " +
                        "presence=${observation.presenceInventory.discoveryReport.aggregate} " +
                        "windows=${batch.windows.size}",
                )
                if (observation.followUpRequired) {
                    postCommit.requestDirtyFollowUp(LibraryOperationCause.FOREGROUND_CATCH_UP)
                }
            }
        }
    }

    private suspend fun executeDeviceNoChangeRetryAuthority(
        operation: ScheduledLibraryOperation,
        token: LibraryOperationToken,
        before: LibraryShadowObservationStamp,
        observation: DeviceAutoSyncShadowObservation.NoChange,
        persistedCheckpoints: List<LibrarySyncCheckpoint>,
        postCommit: AutoSyncPostCommitCollector,
    ): Boolean {
        val nowMs = backing.scanEnvironment.currentTimeMillis()
        val currentSongs = backing.songs
        val dueRetryItems = withContext(backing.ioDispatcher) {
            backing.libraryStore.loadDueRetryItems(
                sourceIdentity = before.sourceActivation.sourceIdentity,
                retryKind = LibraryRetryKind.OBJECT_PROBE,
                activationEpoch = token.activationEpoch,
                nowMs = nowMs,
                limit = LibraryRetryPaging.DUE_WORK_BUDGET,
            )
        }
        if (dueRetryItems.isEmpty()) {
            val nextDelayMs = withContext(backing.ioDispatcher) {
                backing.libraryStore.loadNextRetryAtMsAfter(
                    sourceIdentity = token.sourceIdentity,
                    activationEpoch = token.activationEpoch,
                    afterMs = nowMs,
                )
            }?.let { nextRetryAtMs ->
                (nextRetryAtMs - nowMs).coerceAtLeast(0L)
            }
            requestDeviceRetryWake(
                postCommit = postCommit,
                token = token,
                publishAuthority = true,
                delayMs = nextDelayMs,
            )
            return false
        }

        val excludedStableObjectKeys = withContext(backing.ioDispatcher) {
            backing.libraryStore.loadUserExclusions(before.sourceActivation.sourceIdentity)
                .mapTo(linkedSetOf(), LibraryUserExclusion::stableObjectKey)
        }
        val retryItemsForProbe = dueRetryItems.filterNot {
            it.stableObjectKey in excludedStableObjectKeys
        }
        val scanOptions = LibraryScanSettings.scanOptions(backing.context)
        val retryObservation = withContext(backing.ioDispatcher) {
            backing.deviceRetryObservationRuntime.resolve(
                DeviceRetryObservationRequest(
                    currentSongs = currentSongs,
                    retryItems = retryItemsForProbe,
                    nowMs = nowMs,
                    scanOptions = scanOptions,
                ),
            )
        }
        val probePlan = DeviceAutoProbePlanner.plan(
            candidates = DeviceDeltaCandidatePlan(
                audioCandidates = emptyList(),
                sidecarCandidates = emptyList(),
                contradictions = emptyList(),
            ),
            currentSongs = currentSongs,
            retryItems = retryItemsForProbe,
            sourceIdentity = token.sourceIdentity,
            activationEpoch = token.activationEpoch,
            nowMs = nowMs,
            playback = backing.playbackIoSnapshot(),
            retryObservationRowsByStableObjectKey =
                retryObservation.observedRowsByStableObjectKey,
            excludedStableObjectKeys = excludedStableObjectKeys,
        )
        val lyricsInventory = retryObservation.lyricsInventory
        val canExecuteReadyProbe = lyricsInventory?.complete == true
        val execution = if (probePlan.ready.isNotEmpty() && canExecuteReadyProbe) {
            withContext(backing.ioDispatcher) {
                backing.deviceShadowProbeRuntime.execute(
                    DeviceShadowProbeRequest(
                        probePlan = probePlan,
                        scanOptions = scanOptions,
                        lyricsInventory = requireNotNull(lyricsInventory),
                        currentSongs = currentSongs,
                        currentSourceIdentity = token.sourceIdentity,
                        currentActivationEpoch = token.activationEpoch,
                        folderCasingPlan = DeviceDeltaFolderCasingPlan.Empty,
                        playbackSnapshotProvider = backing::playbackIoSnapshot,
                    ),
                )
            }
        } else if (probePlan.ready.isNotEmpty()) {
            DeviceShadowProbeExecutionResult(
                resolvedObjectsByStableObjectKey = emptyMap(),
                issues = probePlan.ready.map { plan ->
                    DeviceShadowProbeIssue(
                        stableObjectKey = plan.stableObjectKey,
                        kind = DeviceShadowProbeIssueKind.DRAFT_UNAVAILABLE,
                        detail = "retry-lyrics-inventory-unavailable",
                    )
                },
            )
        } else {
            DeviceShadowProbeExecutionResult(
                resolvedObjectsByStableObjectKey = emptyMap(),
                issues = emptyList(),
            )
        }
        backing.hasPlaybackDeferredAutoWork =
            probePlan.deferred.isNotEmpty() ||
                execution.playbackDeferredKeys.isNotEmpty()

        val retryPlan = DeviceShadowRetryPlanner.plan(
            sourceIdentity = token.sourceIdentity,
            activationEpoch = token.activationEpoch,
            nowMs = nowMs,
            probePlan = probePlan,
            existingRetryItems = dueRetryItems,
            execution = execution,
            authoritativeRemovedStableObjectKeys = excludedStableObjectKeys,
            retryObservationMissingStableObjectKeys =
                retryObservation.missingStableObjectKeys,
            retryObservationUnavailableStableObjectKeys =
                retryObservation.unavailableStableObjectKeys,
        )
        val publicationPlan = DeviceAutoSyncPublicationPlanner.plan(
            minDurationMs = LibraryScanSettings.scanOptions(backing.context).minDurationMs,
            sourceIdentity = token.sourceIdentity,
            configFingerprint = token.configFingerprint,
            nowMs = nowMs,
            currentSongs = currentSongs,
            advanceTo = observation.snapshot,
            existingCheckpoints = persistedCheckpoints,
            membershipPlan = AutoSyncMembershipPlan.Apply(emptyList()),
            probePlan = probePlan,
            execution = execution,
            retryPlan = retryPlan,
            excludedStableObjectKeys = excludedStableObjectKeys,
        )
        val publicationResult = publicationAuthority.publishDevicePlan(
            token = token,
            scanStartSnapshot = currentSongs,
            plan = publicationPlan,
        )
        if (publicationResult == null) {
            DiagnosticLog.event(
                "LibraryAutoSync",
                "device retry stale-drop-publication request=${operation.requestSequence}",
            )
            return true
        }

        val playbackDeferredKeys = buildSet {
            probePlan.deferred.mapTo(this, DeviceAutoProbeObjectPlan::stableObjectKey)
            addAll(execution.playbackDeferredKeys)
        }
        val retryNowMs = backing.scanEnvironment.currentTimeMillis()
        var nextDelayMs = nextDeviceRetryDelayFromStore(
            token = token,
            nowMs = retryNowMs,
            playbackDeferredStableObjectKeys = playbackDeferredKeys,
        )
        if (
            probePlan.ready.isNotEmpty() &&
            !canExecuteReadyProbe &&
            nextDelayMs == null
        ) {
            nextDelayMs = DEVICE_REQUERY_RETRY_DELAY_MS
        }
        val retryWakeRequested = requestDeviceRetryWake(
            postCommit = postCommit,
            token = token,
            publishAuthority = true,
            delayMs = nextDelayMs,
        )
        DiagnosticLog.event(
            "LibraryAutoSync",
            "device retry no-change request=${operation.requestSequence} " +
                "due=${dueRetryItems.size} " +
                "reobserved=${retryObservation.observedRowsByStableObjectKey.size} " +
                "missing=${retryObservation.missingStableObjectKeys.size} " +
                "unavailable=${retryObservation.unavailableStableObjectKeys.size} " +
                "probeReady=${probePlan.ready.size} " +
                "probeRequery=${probePlan.requeryRequired.size} " +
                "probeDeferred=${probePlan.deferred.size} " +
                "probeResolved=${execution.resolvedSongsByStableObjectKey.size} " +
                "probeIssues=${execution.issues.size} " +
                "inventoryReady=$canExecuteReadyProbe " +
                "retryUpserts=${publicationPlan.autoSyncStateMutation.retryUpserts.size} " +
                "retryDeletes=${publicationPlan.autoSyncStateMutation.retryDeleteKeys.size} " +
                "nextDelayMs=${nextDelayMs ?: -1L} " +
                "retryWakeRequested=$retryWakeRequested",
        )
        return true
    }

    private fun requestDeviceRetryWake(
        postCommit: AutoSyncPostCommitCollector,
        token: LibraryOperationToken,
        publishAuthority: Boolean,
        delayMs: Long?,
    ): Boolean {
        if (!publishAuthority) return false
        postCommit.requestRetryWake(
            cause = LibraryOperationCause.DEVICE_RETRY_DUE,
            delayMs = delayMs,
            sourceIdentity = token.sourceIdentity,
            activationEpoch = token.activationEpoch,
        )
        return true
    }

    private suspend fun nextDeviceRetryDelayFromStore(
        token: LibraryOperationToken,
        nowMs: Long,
        playbackDeferredStableObjectKeys: Set<String> = emptySet(),
    ): Long? {
        val due = withContext(backing.ioDispatcher) {
            backing.libraryStore.loadDueRetryItems(
                sourceIdentity = token.sourceIdentity,
                retryKind = LibraryRetryKind.OBJECT_PROBE,
                activationEpoch = token.activationEpoch,
                nowMs = nowMs,
                limit = LibraryRetryPaging.DUE_WORK_BUDGET,
            )
        }
        if (due.any { it.stableObjectKey !in playbackDeferredStableObjectKeys }) {
            return DEVICE_REQUERY_RETRY_DELAY_MS
        }

        val nextRetryAtMs = withContext(backing.ioDispatcher) {
            backing.libraryStore.loadNextRetryAtMsAfter(
                sourceIdentity = token.sourceIdentity,
                activationEpoch = token.activationEpoch,
                afterMs = nowMs,
            )
        } ?: return null
        return (nextRetryAtMs - nowMs).coerceAtLeast(0L)
    }

}

internal fun LibraryShadowObservationStamp.matchesOperationToken(
    token: LibraryOperationToken,
): Boolean =
    libraryGeneration == token.libraryGeneration &&
        sourceActivation.sourceIdentity == token.sourceIdentity &&
        sourceActivation.activationEpoch == token.activationEpoch &&
        configFingerprint == token.configFingerprint &&
        intent == LibraryIntentState.ACTIVE &&
        access == LibraryAccessState.AVAILABLE

internal fun deviceShadowConfigKey(
    configFingerprint: String,
    activationEpoch: Long,
): String = "$configFingerprint|activation=$activationEpoch"

internal fun logDeviceShadowCanonicalCoverage(
    requestSequence: Long,
    result: DeviceShadowCanonicalCoverageResult,
) {
    when (result) {
        is DeviceShadowCanonicalCoverageResult.BaselineEstablished ->
            DiagnosticLog.event(
                "LibraryAutoSync",
                "device shadow canonical-baseline request=$requestSequence songs=${result.songCount}",
            )

        is DeviceShadowCanonicalCoverageResult.ContextReset ->
            DiagnosticLog.event(
                "LibraryAutoSync",
                "device shadow canonical-context-reset request=$requestSequence " +
                    "activation=${result.previous.activationEpoch}->${result.current.activationEpoch} " +
                    "sourceChanged=${result.previous.sourceIdentityStorageKey != result.current.sourceIdentityStorageKey} " +
                    "configChanged=${result.previous.configFingerprint != result.current.configFingerprint} " +
                    "identityDomainChanged=" +
                    "${result.previous.providerIdentityDomain != result.current.providerIdentityDomain} " +
                    "songs=${result.songCount}",
            )

        is DeviceShadowCanonicalCoverageResult.Compared -> {
            val uncoveredAspects = result.uncoveredAspectsByStableObjectKey.values
                .flatten()
                .groupingBy { it }
                .eachCount()
                .toSortedMap(compareBy { it.name })
                .entries
                .joinToString(separator = ",") { (aspect, count) -> "${aspect.name}:$count" }
                .ifBlank { "none" }
            DiagnosticLog.event(
                "LibraryAutoSync",
                "device shadow canonical-coverage request=$requestSequence " +
                    "changed=${result.diff.changes.size} " +
                    "covered=${result.coveredStableObjectKeys.size} " +
                    "uncovered=${result.uncoveredAspectsByStableObjectKey.size} " +
                    "fullyCovered=${result.fullyCovered} " +
                    "uncoveredAspects=$uncoveredAspects",
            )
        }
    }
}

internal fun logDeviceShadowCanonicalProjection(
    requestSequence: Long,
    result: DeviceShadowCanonicalProjectionGateResult,
) {
    when (result) {
        is DeviceShadowCanonicalProjectionGateResult.BaselineEstablished ->
            DiagnosticLog.event(
                "LibraryAutoSync",
                "device shadow projection-baseline request=$requestSequence " +
                    "songs=${result.songCount}",
            )

        is DeviceShadowCanonicalProjectionGateResult.ContextReset ->
            DiagnosticLog.event(
                "LibraryAutoSync",
                "device shadow projection-context-reset request=$requestSequence " +
                    "songs=${result.songCount}",
            )

        is DeviceShadowCanonicalProjectionGateResult.Compared -> {
            val equivalence = result.equivalence
            val unresolvedAspects =
                equivalence.projection.unresolvedAspectsByStableObjectKey.values
                    .flatten()
                    .groupingBy { it }
                    .eachCount()
                    .toSortedMap(compareBy { it.name })
                    .entries
                    .joinToString(separator = ",") { (aspect, count) ->
                        "${aspect.name}:$count"
                    }
                    .ifBlank { "none" }
            DiagnosticLog.event(
                "LibraryAutoSync",
                "device shadow projection-compare request=$requestSequence " +
                    "diff=${equivalence.diff.changes.size} " +
                    "unresolvedObjects=" +
                    "${equivalence.projection.unresolvedAspectsByStableObjectKey.size} " +
                    "quarantined=" +
                    "${equivalence.projection.quarantinedMembershipKeys.size} " +
                    "normalizedPendingKeep=${result.normalizedPendingKeepKeys.size} " +
                    "fullyEquivalent=${equivalence.fullyEquivalent} " +
                    "unresolvedAspects=$unresolvedAspects",
            )
        }
    }
}


private const val DEVICE_REQUERY_RETRY_DELAY_MS = 30_000L
