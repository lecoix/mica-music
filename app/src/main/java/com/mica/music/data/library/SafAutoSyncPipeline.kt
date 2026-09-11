package com.mica.music.data.library

import com.mica.music.data.ScanSource
import com.mica.music.data.Song
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.data.scanner.*
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * SAF/FOLDER-specific AUTO inventory/verify/probe/retry pipeline.
 *
 * R3 is a mechanical ownership extraction. Scheduler callbacks remain intact until R4.
 */
internal class SafAutoSyncPipeline(
    private val backing: MusicLibraryBacking,
    private val publicationAuthority: AutoSyncPublicationAuthority,
    private val safShadowCanonicalProjection: SafShadowCanonicalProjectionTracker,
    private val safShadowVideoInventory: SafShadowVideoInventoryTracker,
    private val safProviderDiscoveryBackoff: SafProviderDiscoveryBackoff,
) {
    suspend fun execute(
        operation: ScheduledLibraryOperation,
        token: LibraryOperationToken,
        scheduleBudgetContinuation: Boolean,
        publishAuthority: Boolean,
    ): AutoSyncPostCommit {
        val postCommit = AutoSyncPostCommitCollector()
        executeInternal(
            operation = operation,
            token = token,
            scheduleBudgetContinuation = scheduleBudgetContinuation,
            publishAuthority = publishAuthority,
            postCommit = postCommit,
        )
        return postCommit.build()
    }

    private suspend fun executeInternal(
        operation: ScheduledLibraryOperation,
        token: LibraryOperationToken,
        scheduleBudgetContinuation: Boolean,
        publishAuthority: Boolean,
        postCommit: AutoSyncPostCommitCollector,
    ) {
        val before = backing.captureShadowObservationStamp(ScanSource.FOLDER) ?: return
        if (!before.matchesOperationToken(token)) return
        val treeUriString = token.sourceIdentity.folderTreeUriOrNull() ?: return
        val treeUri = android.net.Uri.parse(treeUriString)
        val nowMs = backing.scanEnvironment.currentTimeMillis()
        val providerScopeKey =
            "${token.sourceIdentity.storageKey()}|" +
                "activation=${token.activationEpoch}|" +
                "config=${token.configFingerprint}"
        var providerRetryDelayMs: Long? = null
        when (
            val permit = safProviderDiscoveryBackoff.permit(
                scopeKey = providerScopeKey,
                nowMs = backing.scanEnvironment.elapsedRealtimeMillis(),
                bypassSlowSuccessCadence =
                    operation.request.cause == LibraryOperationCause.PLAYBACK_IO_RELEASE,
            )
        ) {
            SafProviderDiscoveryPermit.Allowed -> Unit
            is SafProviderDiscoveryPermit.BackedOff -> {
                requestSafRetryWake(postCommit, token, publishAuthority, permit.remainingMs)
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "saf shadow discovery-backoff request=${operation.requestSequence} " +
                        "failures=${permit.failureCount} remainingMs=${permit.remainingMs} " +
                        "nextAllowedAt=${permit.nextAllowedAtMs} " +
                        "lastFailure=${permit.lastFailureDetail}",
                )
                return
            }
            is SafProviderDiscoveryPermit.SlowSuccessCadence -> {
                requestSafRetryWake(postCommit, token, publishAuthority, permit.remainingMs)
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "saf shadow discovery-cadence request=${operation.requestSequence} " +
                        "remainingMs=${permit.remainingMs} " +
                        "nextAllowedAt=${permit.nextAllowedAtMs} " +
                        "lastWalkMs=${permit.lastCompletedWallTimeMs} " +
                        "slowThresholdMs=${permit.slowSuccessThresholdMs} " +
                        "cadenceMs=${permit.cadenceMs}",
                )
                return
            }
        }
        if (!backing.scanEnvironment.canReadTree(treeUri)) {
            val persistedGrant =
                backing.scanEnvironment.hasPersistedTreeReadAccess(treeUri)
            val providerAcquired = persistedGrant &&
                backing.scanEnvironment.canAcquireTreeProvider(treeUri)
            val recoveredAfterReacquire = providerAcquired &&
                backing.scanEnvironment.canReadTree(treeUri)
            if (recoveredAfterReacquire) {
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "saf provider reacquired request=${operation.requestSequence} " +
                        "persistedGrant=true uri=$treeUri",
                )
            } else {
                val providerClientUnavailable = persistedGrant && !providerAcquired
                val failureDetail = if (providerClientUnavailable) {
                    "provider-client-unavailable"
                } else {
                    "cannot-read-tree"
                }
                val failure = safProviderDiscoveryBackoff.recordFailure(
                    scopeKey = providerScopeKey,
                    nowMs = backing.scanEnvironment.elapsedRealtimeMillis(),
                    detail = failureDetail,
                )
                if (
                    publishAuthority &&
                    providerClientUnavailable &&
                    failure.failureCount >= SAF_PROVIDER_RESELECT_FAILURE_THRESHOLD
                ) {
                    backing.lastScanError = SAF_PROVIDER_RESELECT_REQUIRED_ERROR
                    backing.launchAccessStateUpdate(LibraryAccessState.TEMP_UNAVAILABLE)
                    DiagnosticLog.event(
                        "LibraryAutoSync",
                        "saf shadow unavailable request=${operation.requestSequence} " +
                            "reason=$failureDetail failures=${failure.failureCount} " +
                            "recovery=user-reselect-or-resume retryWake=false",
                    )
                    return
                }
                requestSafRetryWake(postCommit, token, publishAuthority, failure.delayMs)
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "saf shadow unavailable request=${operation.requestSequence} " +
                        "reason=$failureDetail failures=${failure.failureCount} " +
                        "persistedGrant=$persistedGrant providerAcquired=$providerAcquired " +
                        "backoffMs=${failure.delayMs}",
                )
                return
            }
        }

        val snapshot = try {
            withContext(backing.ioDispatcher) {
                backing.libraryScanner.observeFolderMetadata(treeUri)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val detail = "metadata-walk:" +
                error.javaClass.simpleName + ":" +
                error.message.orEmpty().ifBlank { "no-message" }
            val failure = safProviderDiscoveryBackoff.recordFailure(
                scopeKey = providerScopeKey,
                nowMs = backing.scanEnvironment.elapsedRealtimeMillis(),
                detail = detail,
            )
            requestSafRetryWake(postCommit, token, publishAuthority, failure.delayMs)
            DiagnosticLog.event(
                "LibraryAutoSync",
                "saf shadow discovery-failed request=${operation.requestSequence} " +
                    "phase=initial failures=${failure.failureCount} " +
                    "backoffMs=${failure.delayMs} detail=$detail",
                error,
            )
            return
        }
        val afterMetadataWalk = backing.captureShadowObservationStamp(ScanSource.FOLDER)
        if (afterMetadataWalk != before) {
            DiagnosticLog.event(
                "LibraryAutoSync",
                "saf shadow stale-drop request=${operation.requestSequence} " +
                    "dirty=${operation.dirtySequenceAtStart}",
            )
            return
        }
        if (snapshot.discoveryReport.isComplete(DiscoveryPartitions.SAF_TREE)) {
            if (backing.lastScanError == SAF_PROVIDER_RESELECT_REQUIRED_ERROR) {
                backing.lastScanError = null
            }
            val completeState = safProviderDiscoveryBackoff.recordComplete(
                scopeKey = providerScopeKey,
                nowMs = backing.scanEnvironment.elapsedRealtimeMillis(),
                wallTimeMs = snapshot.observationStats.wallTimeMs,
            )
            if (completeState.slowSuccess) {
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "saf shadow discovery-slow-success request=${operation.requestSequence} " +
                        "phase=initial wallMs=${completeState.wallTimeMs} " +
                        "thresholdMs=${completeState.slowSuccessThresholdMs} " +
                        "cadenceMs=${completeState.cadenceMs} " +
                        "nextAllowedAt=${completeState.nextAllowedAtMs}",
                )
            }
        } else {
            val failure = safProviderDiscoveryBackoff.recordFailure(
                scopeKey = providerScopeKey,
                nowMs = backing.scanEnvironment.elapsedRealtimeMillis(),
                detail = "metadata-incomplete:${snapshot.discoveryReport.aggregate}",
            )
            providerRetryDelayMs = earlierRetryDelay(providerRetryDelayMs, failure.delayMs)
            DiagnosticLog.event(
                "LibraryAutoSync",
                "saf shadow discovery-incomplete request=${operation.requestSequence} " +
                    "failures=${failure.failureCount} backoffMs=${failure.delayMs} " +
                    "completeness=${snapshot.discoveryReport.aggregate}",
            )
        }

        val excludedStableObjectKeys = withContext(backing.ioDispatcher) {
            backing.libraryStore.loadUserExclusions(token.sourceIdentity)
                .mapTo(linkedSetOf(), LibraryUserExclusion::stableObjectKey)
        }
        val plan = SafFastVerifyPlanner.plan(
            snapshot = snapshot,
            cachedSongs = backing.songs,
            excludedStableObjectKeys = excludedStableObjectKeys,
            cachedSongById = backing::songById,
        )
        val allowUnknownFingerprintVerify = when (operation.request.cause) {
            LibraryOperationCause.SAF_TREE_DIRTY,
            LibraryOperationCause.SAF_PERIODIC_VERIFY,
            LibraryOperationCause.PLAYBACK_IO_RELEASE,
            LibraryOperationCause.SAF_RETRY_DUE,
            -> true
            else -> false
        }
        val alreadyResolvedAudioKeys = safShadowCanonicalProjection.resolvedAudioStableObjectKeys(
            entries = plan.added + plan.changed + plan.unknownFingerprint,
        )
        val unknownCandidatesForRetry = plan.unknownFingerprint.filterNot {
            it.stableObjectKey in alreadyResolvedAudioKeys
        }
        val retryCleanupCandidateKeys = buildSet {
            snapshot.entries.asSequence()
                .filter {
                    it.fingerprintReliability !=
                        com.mica.music.data.scanner.SafFingerprintReliability.UNKNOWN
                }
                .mapTo(this, com.mica.music.data.scanner.SafTreeMetadataEntry::stableObjectKey)
            addAll(plan.removedStableObjectKeys)
        }
        val retryWorkingSet = withContext(backing.ioDispatcher) {
            SafRetryPlanningLoader.load(
                sourceIdentity = token.sourceIdentity,
                activationEpoch = token.activationEpoch,
                nowMs = nowMs,
                unknownCandidates = unknownCandidatesForRetry,
                cleanupCandidateStableObjectKeys = retryCleanupCandidateKeys,
                allowUnknownFingerprintVerify = allowUnknownFingerprintVerify,
                loadDueObjectRetries = { limit ->
                    backing.libraryStore.loadDueRetryItems(
                        sourceIdentity = token.sourceIdentity,
                        retryKind = LibraryRetryKind.OBJECT_PROBE,
                        activationEpoch = token.activationEpoch,
                        nowMs = nowMs,
                        limit = limit,
                    )
                },
                loadByStableObjectKeys = { stableObjectKeys ->
                    backing.libraryStore.loadRetryItemsForStableObjectKeys(
                        token.sourceIdentity,
                        stableObjectKeys,
                    )
                },
            )
        }
        val retryItems = retryWorkingSet.retryItems
        val safPlaybackSnapshotProvider = {
            backing.playbackIoSnapshot().withSafProviderSerialization(treeUri.authority)
        }
        val probePlan = SafAutoProbePlanner.plan(
            verifyPlan = plan,
            playback = safPlaybackSnapshotProvider(),
            observedEntries = snapshot.entries,
            retryItems = retryItems,
            sourceIdentity = token.sourceIdentity,
            activationEpoch = token.activationEpoch,
            nowMs = nowMs,
            allowUnknownFingerprintVerify = allowUnknownFingerprintVerify,
            alreadyResolvedStableObjectKeys = alreadyResolvedAudioKeys,
            preselectedUnknownFingerprintVerify =
                retryWorkingSet.preselectedUnknownFingerprintVerify,
            unknownFingerprintDueCountOverride = retryWorkingSet.unknownFingerprintDueCount,
        )
        val metadataWorkKeys =
            (plan.added + plan.changed + plan.unknownFingerprint)
                .mapTo(hashSetOf(), com.mica.music.data.scanner.SafTreeMetadataEntry::stableObjectKey)
        val unchangedProbeCount =
            probePlan.objects.count { it.stableObjectKey !in metadataWorkKeys }
        backing.hasPlaybackDeferredAutoWork = probePlan.deferred.isNotEmpty()
        val currentSongs = backing.songs
        val currentRelationFolders = currentSongs.asSequence()
            .filter { it.videoCoverUri != null || it.musicVideoUri != null }
            .mapTo(linkedSetOf(), Song::folderPath)
        val changedVideoFolders = safShadowVideoInventory.changedFolders(
            files = snapshot.videoCovers,
            conservativeFoldersWhenUnseeded = currentRelationFolders,
        )
        val audioWorkEntries = probePlan.objects.map(SafAutoProbeObjectPlan::entry)
        val observedVideoFolders =
            snapshot.videoCovers.mapTo(linkedSetOf(), com.mica.music.data.scanner.VideoCoverFile::folderPath)
        val relationPotentialFolders = SafShadowRelationRematcher.potentialAffectedFolders(
            currentSongs = currentSongs,
            audioWorkEntries = audioWorkEntries,
            removedStableObjectKeys = plan.removedStableObjectKeys,
            changedVideoFolderPaths = changedVideoFolders,
            observedVideoFolderPaths = observedVideoFolders,
        )

        var execution = SafShadowProbeExecutionResult()
        if (probePlan.ready.isNotEmpty() || probePlan.budgetDeferred.isNotEmpty()) {
            execution = withContext(backing.ioDispatcher) {
                backing.safShadowProbeRuntime.execute(
                    SafShadowProbeRequest(
                        probePlan = probePlan,
                        scanOptions = LibraryScanSettings.scanOptions(backing.context),
                        currentSongs = currentSongs,
                        playbackSnapshotProvider = safPlaybackSnapshotProvider,
                    ),
                )
            }
            if (execution.playbackDeferredKeys.isNotEmpty()) {
                backing.hasPlaybackDeferredAutoWork = true
            }
        }

        val requiresPostValidation =
            execution.provisionalSongsByStableObjectKey.isNotEmpty() ||
                relationPotentialFolders.isNotEmpty()
        val postSnapshot = if (requiresPostValidation) {
            val observed = try {
                withContext(backing.ioDispatcher) {
                    backing.libraryScanner.observeFolderMetadata(treeUri)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val detail = "post-metadata-walk:" +
                    error.javaClass.simpleName + ":" +
                    error.message.orEmpty().ifBlank { "no-message" }
                val failure = safProviderDiscoveryBackoff.recordFailure(
                    scopeKey = providerScopeKey,
                    nowMs = backing.scanEnvironment.elapsedRealtimeMillis(),
                    detail = detail,
                )
                requestSafRetryWake(postCommit, token, publishAuthority, failure.delayMs)
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "saf shadow discovery-failed request=${operation.requestSequence} " +
                        "phase=post failures=${failure.failureCount} " +
                        "backoffMs=${failure.delayMs} detail=$detail",
                    error,
                )
                return
            }
            val afterPostValidationWalk =
                backing.captureShadowObservationStamp(ScanSource.FOLDER)
            if (afterPostValidationWalk != before) {
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "saf shadow stale-drop-post-probe request=${operation.requestSequence} " +
                        "dirty=${operation.dirtySequenceAtStart}",
                )
                return
            }
            if (observed.discoveryReport.isComplete(DiscoveryPartitions.SAF_TREE)) {
                val completeState = safProviderDiscoveryBackoff.recordComplete(
                    scopeKey = providerScopeKey,
                    nowMs = backing.scanEnvironment.elapsedRealtimeMillis(),
                    wallTimeMs = observed.observationStats.wallTimeMs,
                )
                if (completeState.slowSuccess) {
                    DiagnosticLog.event(
                        "LibraryAutoSync",
                        "saf shadow discovery-slow-success request=${operation.requestSequence} " +
                            "phase=post wallMs=${completeState.wallTimeMs} " +
                            "thresholdMs=${completeState.slowSuccessThresholdMs} " +
                            "cadenceMs=${completeState.cadenceMs} " +
                            "nextAllowedAt=${completeState.nextAllowedAtMs}",
                    )
                }
            } else {
                val failure = safProviderDiscoveryBackoff.recordFailure(
                    scopeKey = providerScopeKey,
                    nowMs = backing.scanEnvironment.elapsedRealtimeMillis(),
                    detail = "post-metadata-incomplete:${observed.discoveryReport.aggregate}",
                )
                providerRetryDelayMs = earlierRetryDelay(providerRetryDelayMs, failure.delayMs)
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "saf shadow discovery-incomplete request=${operation.requestSequence} " +
                        "phase=post failures=${failure.failureCount} " +
                        "backoffMs=${failure.delayMs} " +
                        "completeness=${observed.discoveryReport.aggregate}",
                )
            }
            observed
        } else {
            null
        }

        val validation = if (postSnapshot != null) {
            SafShadowPostProbeValidator.validate(
                initialSnapshot = snapshot,
                postSnapshot = postSnapshot,
                execution = execution,
            )
        } else {
            SafShadowPostValidationResult(
                resolvedSongsByStableObjectKey = emptyMap(),
                issues = execution.issues,
            )
        }
        val retryPlanNowMs = backing.scanEnvironment.currentTimeMillis()
        val retryableIssueStableObjectKeys = validation.issues.asSequence()
            .filter { issue ->
                when (issue.kind) {
                    SafShadowProbeIssueKind.DRAFT_UNAVAILABLE,
                    SafShadowProbeIssueKind.PROBE_FAILED,
                    SafShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
                    -> true
                    SafShadowProbeIssueKind.PLAYBACK_DEFERRED,
                    SafShadowProbeIssueKind.UNKNOWN_VERIFY_BUDGET_DEFERRED,
                    SafShadowProbeIssueKind.UNVERIFIABLE_FINGERPRINT,
                    -> false
                }
            }
            .map(SafShadowProbeIssue::stableObjectKey)
            .distinct()
            .take(LibraryRetryPaging.DUE_WORK_BUDGET)
            .toList()
        val retryOutcomeItems = if (retryableIssueStableObjectKeys.isEmpty()) {
            emptyList()
        } else {
            withContext(backing.ioDispatcher) {
                backing.libraryStore.loadRetryItemsForStableObjectKeys(
                    token.sourceIdentity,
                    retryableIssueStableObjectKeys,
                )
            }
        }
        val retryItemsForMutation = linkedMapOf<String, LibraryRetryItem>().apply {
            retryItems.forEach { put(it.retryKey, it) }
            retryOutcomeItems.forEach { put(it.retryKey, it) }
        }.values.toList()
        val shadowRetryPlan = SafShadowRetryPlanner.plan(
            sourceIdentity = token.sourceIdentity,
            activationEpoch = token.activationEpoch,
            nowMs = retryPlanNowMs,
            observedEntries = snapshot.entries,
            existingRetryItems = retryItemsForMutation,
            validation = validation,
            authoritativeRemovedStableObjectKeys = plan.removedStableObjectKeys,
        )
        val unknownDebtPlan = SafUnknownFingerprintDebtPlanner.plan(
            sourceIdentity = token.sourceIdentity,
            activationEpoch = token.activationEpoch,
            nowMs = retryPlanNowMs,
            allObservedEntries = snapshot.entries,
            existingRetryItems = retryItemsForMutation,
            probePlan = probePlan,
            execution = execution,
            validation = validation,
            authoritativeRemovedStableObjectKeys = plan.removedStableObjectKeys,
        )

        val provisionalRelations = SafShadowRelationRematcher.rematch(
            snapshot = snapshot,
            currentSongs = currentSongs,
            audioWorkEntries = audioWorkEntries,
            removedStableObjectKeys = plan.removedStableObjectKeys,
            resolvedAudioSongsByStableObjectKey = validation.resolvedSongsByStableObjectKey,
            affectedFolderPaths = relationPotentialFolders,
        )
        val relationValidation = if (postSnapshot != null) {
            SafShadowRelationPostValidator.validate(
                initialSnapshot = snapshot,
                postSnapshot = postSnapshot,
                provisional = provisionalRelations,
            )
        } else {
            provisionalRelations
        }
        val finalStamp = backing.captureShadowObservationStamp(ScanSource.FOLDER)
        if (finalStamp != before || !before.matchesOperationToken(token)) {
            DiagnosticLog.event(
                "LibraryAutoSync",
                "saf shadow stale-drop-pre-publication-plan request=${operation.requestSequence}",
            )
            return
        }
        val publicationPlan = SafAutoSyncPublicationPlanner.plan(
            minDurationMs = LibraryScanSettings.scanOptions(backing.context).minDurationMs,
            sourceIdentity = token.sourceIdentity,
            activationEpoch = token.activationEpoch,
            configFingerprint = token.configFingerprint,
            nowMs = retryPlanNowMs,
            currentSongs = currentSongs,
            snapshot = snapshot,
            verifyPlan = plan,
            probePlan = probePlan,
            validation = validation,
            relationValidation = relationValidation,
            retryPlan = shadowRetryPlan,
            unknownDebtPlan = unknownDebtPlan,
            excludedStableObjectKeys = excludedStableObjectKeys,
        )
        val unresolvedAudioResourceKeys = audioWorkEntries.asSequence()
            .filter {
                it.fingerprintReliability ==
                    com.mica.music.data.scanner.SafFingerprintReliability.UNKNOWN
            }
            .mapTo(linkedSetOf(), com.mica.music.data.scanner.SafTreeMetadataEntry::stableObjectKey)

        val publicationResult = if (publishAuthority) {
            publicationAuthority.publishSafPlan(
                token = token,
                scanStartSnapshot = currentSongs,
                plan = publicationPlan,
            )
        } else {
            null
        }
        if (publishAuthority && publicationResult == null) {
            DiagnosticLog.event(
                "LibraryAutoSync",
                "saf auto stale-drop-publication request=${operation.requestSequence}",
            )
            return
        }

        val trackersAccepted = backing.withCurrentOperationIfCurrent(token) {
            if (changedVideoFolders.isEmpty()) {
                safShadowVideoInventory.seedIfAbsent(snapshot.videoCovers)
            } else {
                safShadowVideoInventory.acceptFolders(
                    files = snapshot.videoCovers,
                    folderPaths = relationValidation.resolvedFolderPaths,
                )
            }
            safShadowCanonicalProjection.recordDelta(
                verifyPlan = plan,
                resolvedSongsByStableObjectKey = validation.resolvedSongsByStableObjectKey,
                resolvedRelationSongsByStableObjectKey =
                    relationValidation.resolvedSongsByStableObjectKey,
                unresolvedRelationStableObjectKeys =
                    relationValidation.unresolvedStableObjectKeys,
                unresolvedAudioResourceStableObjectKeys = unresolvedAudioResourceKeys,
                audioWorkEntries = audioWorkEntries,
            )
        }
        if (trackersAccepted == null) {
            DiagnosticLog.event(
                "LibraryAutoSync",
                "saf ${if (publishAuthority) "auto" else "shadow"} " +
                    "stale-drop-tracker request=${operation.requestSequence}",
            )
            return
        }

        if (publishAuthority && publicationResult != null) {
            postCommit.requestArtworkHydration(publicationPlan.visibleDelta.addedIds)
        }

        val ledgerRetryDelayMs = if (publishAuthority) {
            withContext(backing.ioDispatcher) {
                backing.libraryStore.loadNextRetryAtMsAfter(
                    sourceIdentity = token.sourceIdentity,
                    activationEpoch = token.activationEpoch,
                    afterMs = retryPlanNowMs,
                )
            }?.let { nextRetryAtMs ->
                (nextRetryAtMs - retryPlanNowMs).coerceAtLeast(0L)
            }
        } else {
            null
        }
        val nextRetryWakeDelayMs = earlierRetryDelay(
            providerRetryDelayMs,
            ledgerRetryDelayMs,
        )
        val retryWakeRequested = if (publishAuthority) {
            requestSafRetryWake(postCommit, token, publishAuthority = true, delayMs = nextRetryWakeDelayMs)
        } else {
            false
        }
        val retryWakeScheduled = nextRetryWakeDelayMs != null && retryWakeRequested

        val hasNonUnknownBudgetDebt = probePlan.budgetDeferred.any { objectPlan ->
            objectPlan.reasons.any {
                it != SafAutoProbeReason.UNKNOWN_FINGERPRINT_VERIFY
            }
        }
        val pureUnknownContinuationShadowHeld =
            !publishAuthority &&
            probePlan.shouldRequestBudgetContinuation(execution) &&
                !hasNonUnknownBudgetDebt
        val budgetContinuationRequested =
            scheduleBudgetContinuation &&
                (publishAuthority || hasNonUnknownBudgetDebt) &&
                probePlan.shouldRequestBudgetContinuation(execution) &&
                backing.isCurrentOperationToken(token) &&
                requestSafBudgetContinuation(postCommit)
        val issueKinds = validation.issues
            .groupingBy(SafShadowProbeIssue::kind)
            .eachCount()
            .entries
            .sortedBy { it.key.name }
            .joinToString(",") { (kind, count) -> "${kind.name}:$count" }
            .ifBlank { "none" }
        DiagnosticLog.event(
            "LibraryAutoSync",
            "saf ${if (publishAuthority) "auto" else "shadow"} verify " +
                "request=${operation.requestSequence} " +
                "entries=${snapshot.entries.size} " +
                "added=${plan.added.size} changed=${plan.changed.size} " +
                "unknownFingerprint=${plan.unknownFingerprint.size} " +
                "unknownDue=${probePlan.unknownFingerprintDueCount} " +
                "unknownSelected=${probePlan.unknownFingerprintSelectedCount} " +
                "unknownNotDue=${probePlan.unknownFingerprintNotDueCount} " +
                "unknownSuppressedByCause=${probePlan.unknownFingerprintSuppressedByCauseCount} " +
                "unknownBudgetDeferred=${probePlan.unknownFingerprintDeferredByBudgetCount} " +
                "unknownDebtUpserts=${unknownDebtPlan.retryUpserts.size} " +
                "unknownDebtDeletes=${unknownDebtPlan.retryDeleteKeys.size} " +
                "unknownStrongVerified=${unknownDebtPlan.strongVerifiedCount} " +
                "unknownVerifyWallMs=${execution.unknownVerifyWallTimeMs} " +
                "unknownVerifyWallBudgetMs=$UNKNOWN_VERIFY_WALL_TIME_BUDGET_MS " +
                "unknownDebtShadowOnly=${!publishAuthority} " +
                "probePreviouslyResolved=${alreadyResolvedAudioKeys.size} " +
                "heavyProbeBudget=${SafAutoProbePlanner.DEFAULT_HEAVY_PROBE_BUDGET} " +
                "heavyProbeBudgetDeferred=${probePlan.budgetDeferred.size} " +
                "dueRetries=${probePlan.dueRetryCount} " +
                "retryMissingObservation=${probePlan.retryMissingObservationCount} " +
                "removed=${plan.removedStableObjectKeys.size} " +
                "removalSuppressed=${plan.removalSuppressedCount} " +
                "unchanged=${plan.unchangedCount} " +
                "probeReady=${probePlan.ready.size} probeDeferred=${probePlan.deferred.size} " +
                "probeAttempted=${execution.attemptedCount} " +
                "probeResolved=${validation.resolvedSongsByStableObjectKey.size} " +
                "probeIssues=${validation.issues.size} issueKinds=$issueKinds " +
                "retryPlanUpserts=${shadowRetryPlan.retryUpserts.size} " +
                "retryPlanDeletes=${shadowRetryPlan.retryDeleteKeys.size} " +
                "retryPlanIgnoredIssues=${shadowRetryPlan.ignoredIssueCount} " +
                "retryPlanShadowOnly=${!publishAuthority} " +
                "retryWakeDelayMs=${nextRetryWakeDelayMs ?: -1L} " +
                "retryWakeScheduled=$retryWakeScheduled " +
                "videoInventoryChangedFolders=${changedVideoFolders.size} " +
                "relationFolders=${relationPotentialFolders.size} " +
                "relationResolved=${relationValidation.resolvedSongsByStableObjectKey.size} " +
                "relationIssues=${relationValidation.issues.size} " +
                "publicationPlanAdded=${publicationPlan.visibleDelta.addedIds.size} " +
                "publicationPlanUpdated=${publicationPlan.visibleDelta.updatedIds.size} " +
                "publicationPlanRemoved=${publicationPlan.visibleDelta.removedStableObjectKeys.size} " +
                "publicationPlanLyrics=${publicationPlan.lyricsToStage.size} " +
                "publicationPlanRetryUpserts=${publicationPlan.autoSyncStateMutation.retryUpserts.size} " +
                "publicationPlanRetryDeletes=${publicationPlan.autoSyncStateMutation.retryDeleteKeys.size} " +
                "publicationPlanCheckpoint=${publicationPlan.checkpointIncluded} " +
                "publicationPlanQuarantine=${publicationPlan.quarantineReason ?: "none"} " +
                "publicationPlanShadowOnly=${!publishAuthority} " +
                "publicationCommitted=${publicationResult != null} " +
                "postWalk=${postSnapshot != null} " +
                "metadataWalkMs=${snapshot.observationStats.wallTimeMs} " +
                "providerQueries=${snapshot.observationStats.providerQueryCount} " +
                "providerDirectQueries=${snapshot.observationStats.directQueryCount} " +
                "providerFallbackListings=${snapshot.observationStats.fallbackListingCount} " +
                "postMetadataWalkMs=${postSnapshot?.observationStats?.wallTimeMs ?: 0L} " +
                "postProviderQueries=${postSnapshot?.observationStats?.providerQueryCount ?: 0} " +
                "totalProviderQueries=" +
                "${snapshot.observationStats.providerQueryCount + (postSnapshot?.observationStats?.providerQueryCount ?: 0)} " +
                "unchangedProbeCount=$unchangedProbeCount " +
                "probeParallelism=${probePlan.heavyProbeParallelism} " +
                "budgetContinuationRequested=$budgetContinuationRequested " +
                "pureUnknownContinuationShadowHeld=$pureUnknownContinuationShadowHeld " +
                "completeness=${snapshot.discoveryReport.aggregate} " +
                "metadataNoOp=${plan.isNoOp} probeNoOp=${probePlan.isNoOp}",
        )
    }

    private fun requestSafRetryWake(
        postCommit: AutoSyncPostCommitCollector,
        token: LibraryOperationToken,
        publishAuthority: Boolean,
        delayMs: Long?,
    ): Boolean {
        if (!publishAuthority) return false
        postCommit.requestRetryWake(
            cause = LibraryOperationCause.SAF_RETRY_DUE,
            delayMs = delayMs,
            sourceIdentity = token.sourceIdentity,
            activationEpoch = token.activationEpoch,
        )
        return true
    }

    private fun requestSafBudgetContinuation(
        postCommit: AutoSyncPostCommitCollector,
    ): Boolean {
        postCommit.requestAutoContinuation(LibraryOperationCause.SAF_BUDGET_CONTINUATION)
        return true
    }

    private fun earlierRetryDelay(
        first: Long?,
        second: Long?,
    ): Long? = when {
        first == null -> second
        second == null -> first
        else -> minOf(first, second)
    }

}

private const val SAF_PROVIDER_RESELECT_FAILURE_THRESHOLD = 3
