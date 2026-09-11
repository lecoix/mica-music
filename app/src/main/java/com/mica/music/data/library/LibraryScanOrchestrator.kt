package com.mica.music.data.library

import android.os.SystemClock
import androidx.core.net.toUri
import com.mica.music.data.AlbumArtRepairAction
import com.mica.music.data.AlbumArtRepairPlan
import com.mica.music.data.CURRENT_LYRICS_PARSER_VERSION
import com.mica.music.data.ScanSource
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.data.SharedLyricsMemoryCache
import com.mica.music.data.Song
import com.mica.music.data.scanner.AutoSyncVisibleDelta
import com.mica.music.data.scanner.DeviceAutoSyncShadowObservation
import com.mica.music.data.scanner.DeviceDeltaCandidatePlan
import com.mica.music.data.scanner.DeviceDeltaCandidatePlanner
import com.mica.music.data.scanner.DeviceDeltaFolderCasingPlan
import com.mica.music.data.scanner.DeviceDeltaFolderCasingPlanner
import com.mica.music.data.scanner.DeviceFolderIdentityResolver
import com.mica.music.data.scanner.DeviceDeltaChannel
import com.mica.music.data.scanner.DeviceLyricsSidecarDiff
import com.mica.music.data.scanner.DeviceLyricsSidecarDiffPlanner
import com.mica.music.data.scanner.DeviceFullScanShadowAnchor
import com.mica.music.data.scanner.DiscoveryPartitions
import com.mica.music.data.scanner.DeviceShadowCanonicalCatalog
import com.mica.music.data.scanner.DeviceShadowCanonicalContext
import com.mica.music.data.scanner.DeviceShadowCanonicalCoverageResult
import com.mica.music.data.scanner.providerIdentityDomainKey
import com.mica.music.data.scanner.resolveMediaStoreDirectoryIdentity
import com.mica.music.data.scanner.ScanResult
import com.mica.music.data.scanner.SafFastVerifyPlanner
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class LibraryScanOrchestrator(
    private val backing: MusicLibraryBacking,
) {
    private data class ScanProbePolicy(
        val forceRefreshLyrics: Boolean,
        val forceRefreshSongIds: Set<String>,
    )

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

    private val catalog get() = backing.catalog
    private val folder get() = backing.folder
    private val deviceShadowCanonicalCoverage = DeviceShadowCanonicalCoverageTracker()
    private val deviceShadowCanonicalProjection = DeviceShadowCanonicalProjectionTracker()
    private val safShadowCanonicalProjection = SafShadowCanonicalProjectionTracker()
    private val safShadowVideoInventory = SafShadowVideoInventoryTracker()
    private val safProviderDiscoveryBackoff = SafProviderDiscoveryBackoff()
    private val autoSyncPublicationAuthority = AutoSyncPublicationAuthority(backing)

    suspend fun rescan() = rescan(null)

    private suspend fun rescan(operation: ScheduledLibraryOperation?) {
        when (backing.lastScanSource) {
            ScanSource.FOLDER -> {
                if (folder.hasLibraryFolder()) {
                    scanLibraryFolder(operation = operation)
                }
            }
            ScanSource.DEVICE -> {
                if (folder.hasAudioReadPermission()) {
                    scanDeviceWide(operation = operation)
                }
            }
        }
    }

    suspend fun scan() = rescan()

    internal fun resetSafProviderDiscoveryBackoff() {
        safProviderDiscoveryBackoff.reset()
    }

    fun launchRescan() {
        backing.syncScheduler.submit(LibraryOperationRequest.Rescan)
    }

    fun launchScanDeviceWide() {
        backing.syncScheduler.submit(LibraryOperationRequest.ScanDeviceWide)
    }

    fun launchScanLibraryFolder() {
        backing.syncScheduler.submit(LibraryOperationRequest.ScanLibraryFolder)
    }

    fun launchArtworkCacheRepair(plan: AlbumArtRepairPlan) {
        backing.syncScheduler.submit(LibraryOperationRequest.ArtworkRepair(plan))
    }

    internal suspend fun executeScheduled(operation: ScheduledLibraryOperation) {
        when (val request = operation.request) {
            LibraryOperationRequest.Rescan -> rescan(operation)
            LibraryOperationRequest.ScanDeviceWide -> scanDeviceWide(operation = operation)
            LibraryOperationRequest.ScanLibraryFolder -> scanLibraryFolder(operation = operation)
            is LibraryOperationRequest.TargetedRefresh ->
                refreshSongMetadata(request.songIds, operation)
            is LibraryOperationRequest.ArtworkRepair ->
                repairArtworkCache(request.plan, operation)
            is LibraryOperationRequest.AutoSync ->
                executeAutoSync(
                    operation,
                    scheduleSafBudgetContinuation = true,
                    publishSafAuthority = true,
                    publishDeviceAuthority = true,
                    enforceAutoSyncGate = true,
                )
        }
    }

    internal suspend fun executeAutoSyncShadowForDiagnostics(
        operation: ScheduledLibraryOperation,
    ) {
        executeAutoSync(
            operation,
            scheduleSafBudgetContinuation = false,
            publishSafAuthority = false,
            publishDeviceAuthority = false,
            enforceAutoSyncGate = false,
        )
    }

    /**
     * Explicit debug/QA authority seam for deterministic SAF publication gates.
     *
     * Production scheduled FOLDER AUTO is enabled after the r5 readiness review; diagnostics that
     * need to suppress authority mutation continue to use [executeAutoSyncShadowForDiagnostics].
     */
    internal suspend fun executeAutoSyncForReadiness(
        operation: ScheduledLibraryOperation,
    ) {
        executeAutoSync(
            operation,
            scheduleSafBudgetContinuation = false,
            publishSafAuthority = true,
            publishDeviceAuthority = true,
            enforceAutoSyncGate = false,
        )
    }

    private suspend fun executeAutoSync(
        operation: ScheduledLibraryOperation,
        scheduleSafBudgetContinuation: Boolean,
        publishSafAuthority: Boolean,
        publishDeviceAuthority: Boolean,
        enforceAutoSyncGate: Boolean,
    ) = backing.operationExecutionMutex.withLock {
        val token = backing.beginActiveAutoSyncOperationToken(
            requestSequence = operation.requestSequence,
            dirtySequenceAtStart = operation.dirtySequenceAtStart,
            cause = operation.request.cause,
            enforceAutoSyncGate = enforceAutoSyncGate,
        ) ?: return@withLock
        executeAutoSyncLocked(
            operation = operation,
            token = token,
            scheduleSafBudgetContinuation = scheduleSafBudgetContinuation,
            publishSafAuthority = publishSafAuthority,
            publishDeviceAuthority = publishDeviceAuthority,
        )
    }

    private suspend fun executeAutoSyncLocked(
        operation: ScheduledLibraryOperation,
        token: LibraryOperationToken,
        scheduleSafBudgetContinuation: Boolean,
        publishSafAuthority: Boolean,
        publishDeviceAuthority: Boolean,
    ) {
        val activeSource = token.sourceIdentity.source
        if (activeSource == ScanSource.FOLDER) {
            executeSafFastVerifyShadow(
                operation = operation,
                token = token,
                scheduleBudgetContinuation = scheduleSafBudgetContinuation,
                publishAuthority = publishSafAuthority,
            )
            return
        }
        if (activeSource != ScanSource.DEVICE) return

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
            deviceShadowCanonicalCoverage.recordDelta(
                candidates = candidates,
                lyricsDiff = lyricsDiff,
                membershipPlan = membershipPlan,
            )
            deviceShadowCanonicalProjection.recordDelta(
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
            publishDeviceAuthority &&
            observation is DeviceAutoSyncShadowObservation.NoChange &&
            executeDeviceNoChangeRetryAuthority(
                operation = operation,
                token = token,
                before = before,
                observation = observation,
                persistedCheckpoints = persistedCheckpoints,
            )
        ) {
            return
        }

        if (
            publishDeviceAuthority &&
            observation is DeviceAutoSyncShadowObservation.DeltaCandidate
        ) {
            val analysis = requireNotNull(deltaAnalysis)
            val publicationPlan = analysis.publicationPlan
            val publicationResult = publishDeviceAutoSyncPlanForReadiness(
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
            val retryWakeScheduled = scheduleDeviceRetryWake(
                token = token,
                publishAuthority = true,
                delayMs = nextRetryDelayMs,
            )

            scheduleAutoArtworkHydration(publicationPlan.visibleDelta.addedIds)

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
                    "retryWakeScheduled=$retryWakeScheduled " +
                    "quarantine=${publicationPlan.quarantineReason ?: "none"}",
            )
            if (observation.followUpRequired) {
                backing.syncScheduler.markDirty(LibraryOperationCause.FOREGROUND_CATCH_UP)
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
                    backing.syncScheduler.markDirty(LibraryOperationCause.FOREGROUND_CATCH_UP)
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
                    backing.syncScheduler.markDirty(LibraryOperationCause.FOREGROUND_CATCH_UP)
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
            scheduleDeviceRetryWake(
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
        val publicationResult = publishDeviceAutoSyncPlanForReadiness(
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
        val retryWakeScheduled = scheduleDeviceRetryWake(
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
                "retryWakeScheduled=$retryWakeScheduled",
        )
        return true
    }

    private fun scheduleSafRetryWake(
        token: LibraryOperationToken,
        publishAuthority: Boolean,
        delayMs: Long?,
    ): Boolean {
        if (!publishAuthority) return false
        return backing.syncScheduler.replaceAutoRetryWake(
            cause = LibraryOperationCause.SAF_RETRY_DUE,
            delayMs = delayMs,
            sourceIdentity = token.sourceIdentity,
            activationEpoch = token.activationEpoch,
        )
    }

    private fun scheduleDeviceRetryWake(
        token: LibraryOperationToken,
        publishAuthority: Boolean,
        delayMs: Long?,
    ): Boolean {
        if (!publishAuthority) return false
        return backing.syncScheduler.replaceAutoRetryWake(
            cause = LibraryOperationCause.DEVICE_RETRY_DUE,
            delayMs = delayMs,
            sourceIdentity = token.sourceIdentity,
            activationEpoch = token.activationEpoch,
        )
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

    private fun earlierRetryDelay(
        first: Long?,
        second: Long?,
    ): Long? = when {
        first == null -> second
        second == null -> first
        else -> minOf(first, second)
    }

    private fun LibraryShadowObservationStamp.matchesOperationToken(
        token: LibraryOperationToken,
    ): Boolean =
        libraryGeneration == token.libraryGeneration &&
            sourceActivation.sourceIdentity == token.sourceIdentity &&
            sourceActivation.activationEpoch == token.activationEpoch &&
            configFingerprint == token.configFingerprint &&
            intent == LibraryIntentState.ACTIVE &&
            access == LibraryAccessState.AVAILABLE

    private fun deviceShadowConfigKey(
        configFingerprint: String,
        activationEpoch: Long,
    ): String = "$configFingerprint|activation=$activationEpoch"

    private fun logDeviceShadowCanonicalCoverage(
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

    private fun logDeviceShadowCanonicalProjection(
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


    private fun logSafShadowCanonicalProjection(
        requestSequence: Long,
        result: SafShadowCanonicalProjectionGateResult,
    ) {
        when (result) {
            is SafShadowCanonicalProjectionGateResult.BaselineEstablished ->
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "saf shadow projection-baseline request=${requestSequence} " +
                        "songs=${result.songCount}",
                )

            is SafShadowCanonicalProjectionGateResult.ContextReset ->
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "saf shadow projection-context-reset request=${requestSequence} " +
                        "activation=${result.previous.activationEpoch}->${result.current.activationEpoch} " +
                        "sourceChanged=" +
                        "${result.previous.sourceIdentityStorageKey != result.current.sourceIdentityStorageKey} " +
                        "configChanged=" +
                        "${result.previous.configFingerprint != result.current.configFingerprint} " +
                        "songs=${result.songCount}",
                )

            is SafShadowCanonicalProjectionGateResult.Compared -> {
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
                val diffAspects = equivalence.diff.changes
                    .flatMap { it.aspects }
                    .groupingBy { it }
                    .eachCount()
                    .toSortedMap(compareBy { it.name })
                    .entries
                    .joinToString(separator = ",") { (aspect, count) ->
                        "${aspect.name}:$count"
                    }
                    .ifBlank { "none" }
                val diffDetails = equivalence.diff.changes.take(4).joinToString(";") { change ->
                    val projected = equivalence.projection.snapshot
                        .songsByStableObjectKey[change.stableObjectKey]
                    val expected = equivalence.expected
                        .songsByStableObjectKey[change.stableObjectKey]
                    "${change.stableObjectKey}:${change.aspects.joinToString("+") { it.name }}:" +
                        "pDateAdded=${projected?.dateAddedMs}:eDateAdded=${expected?.dateAddedMs}"
                }.ifBlank { "none" }
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "saf shadow projection-compare request=$requestSequence " +
                        "diff=${equivalence.diff.changes.size} " +
                        "diffAspects=$diffAspects " +
                        "diffDetails=$diffDetails " +
                        "unresolvedObjects=" +
                        "${equivalence.projection.unresolvedAspectsByStableObjectKey.size} " +
                        "fullyEquivalent=${equivalence.fullyEquivalent} " +
                        "unresolvedAspects=$unresolvedAspects",
                )
            }
        }
    }

    internal suspend fun seedSafShadowCanonicalForDiagnostics() {
        val stamp = backing.captureShadowObservationStamp(ScanSource.FOLDER)
            ?: error("No active FOLDER source available for SAF diagnostics baseline")
        val snapshot = SafShadowCanonicalCatalog.snapshot(
            songs = backing.songs,
            context = SafShadowCanonicalContext(
                sourceIdentityStorageKey =
                    stamp.sourceActivation.sourceIdentity.storageKey(),
                activationEpoch = stamp.sourceActivation.activationEpoch,
                configFingerprint = stamp.configFingerprint,
            ),
        )
        logSafShadowCanonicalProjection(
            requestSequence = 0L,
            result = safShadowCanonicalProjection.acceptFullSnapshot(snapshot),
        )
        // Playback defer Gate uses a baseline without MP4 relations. Seed the empty inventory so
        // metadata-only CHANGED work cannot be misclassified as relation inventory work.
        safShadowVideoInventory.seed(emptyList())
    }

    /** Compatibility seam while readiness tests still target the orchestrator directly. */
    internal suspend fun publishDeviceAutoSyncPlanForReadiness(
        token: LibraryOperationToken,
        scanStartSnapshot: List<Song>,
        plan: DeviceAutoSyncPublicationPlan,
    ): com.mica.music.data.local.LibrarySyncResult? =
        autoSyncPublicationAuthority.publishDevicePlan(token, scanStartSnapshot, plan)

    /** Compatibility seam while readiness tests still target the orchestrator directly. */
    internal suspend fun publishSafAutoSyncPlanForReadiness(
        token: LibraryOperationToken,
        scanStartSnapshot: List<Song>,
        plan: SafAutoSyncPublicationPlan,
    ): com.mica.music.data.local.LibrarySyncResult? =
        autoSyncPublicationAuthority.publishSafPlan(token, scanStartSnapshot, plan)

    /** Compatibility seam for publication atomicity tests; ownership lives in the authority. */
    internal suspend fun publishAutoSyncSnapshot(
        token: LibraryOperationToken,
        scanStartSnapshot: List<Song>,
        nextSnapshot: List<Song>,
        visibleDelta: AutoSyncVisibleDelta,
        membershipChanges: List<MembershipChange>,
        autoSyncStateMutation: LibraryAutoSyncStateMutation,
        stagedLyricsId: String? = null,
        stagedExternalLyricsId: String? = null,
    ): com.mica.music.data.local.LibrarySyncResult? =
        autoSyncPublicationAuthority.publishSnapshot(
            token = token,
            scanStartSnapshot = scanStartSnapshot,
            nextSnapshot = nextSnapshot,
            visibleDelta = visibleDelta,
            membershipChanges = membershipChanges,
            autoSyncStateMutation = autoSyncStateMutation,
            stagedLyricsId = stagedLyricsId,
            stagedExternalLyricsId = stagedExternalLyricsId,
        )

    private suspend fun executeSafFastVerifyShadow(
        operation: ScheduledLibraryOperation,
        token: LibraryOperationToken,
        scheduleBudgetContinuation: Boolean,
        publishAuthority: Boolean,
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
                scheduleSafRetryWake(token, publishAuthority, permit.remainingMs)
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
                scheduleSafRetryWake(token, publishAuthority, permit.remainingMs)
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
                scheduleSafRetryWake(token, publishAuthority, failure.delayMs)
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
            scheduleSafRetryWake(token, publishAuthority, failure.delayMs)
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
                scheduleSafRetryWake(token, publishAuthority, failure.delayMs)
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
            publishSafAutoSyncPlanForReadiness(
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
            scheduleAutoArtworkHydration(publicationPlan.visibleDelta.addedIds)
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
        val retryWakeAccepted = if (publishAuthority) {
            scheduleSafRetryWake(token, publishAuthority = true, delayMs = nextRetryWakeDelayMs)
        } else {
            false
        }
        val retryWakeScheduled = nextRetryWakeDelayMs != null && retryWakeAccepted

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
                backing.syncScheduler.requestAutoContinuation(
                    LibraryOperationCause.SAF_BUDGET_CONTINUATION,
                )
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

    suspend fun scanDeviceWide(
        forceRefreshSongIds: Set<String> = emptySet(),
        userVisible: Boolean = forceRefreshSongIds.isEmpty(),
        operation: ScheduledLibraryOperation? = null,
    ) {
        if (!folder.hasAudioReadPermission()) return
        performScan(
            source = ScanSource.DEVICE,
            requestedForceRefreshLyrics = false,
            forceRefreshSongIds = forceRefreshSongIds,
            userVisible = userVisible,
            operation = operation,
        ) {
                onProgress, cachedSongs, onLyricsBatch, policy ->
            scanDevice(
                cachedSongs = cachedSongs,
                onProgress = onProgress,
                onLyricsBatch = onLyricsBatch,
                policy = policy,
            )
        }
    }

    suspend fun scanLibraryFolder(
        forceRefreshSongIds: Set<String> = emptySet(),
        userVisible: Boolean = forceRefreshSongIds.isEmpty(),
        operation: ScheduledLibraryOperation? = null,
    ) {
        val treeUri = folder.scanTreeUri() ?: return
        if (!backing.scanEnvironment.canReadTree(treeUri)) {
            folder.discardPendingFolderSelection()
            if (userVisible) {
                backing.lastScanError = "鏃犳硶璁块棶鎵€閫夋枃浠跺す锛岃閲嶆柊閫夋嫨"
            }
            return
        }
        performScan(
            source = ScanSource.FOLDER,
            requestedForceRefreshLyrics = false,
            forceRefreshSongIds = forceRefreshSongIds,
            userVisible = userVisible,
            operation = operation,
        ) {
                onProgress, cachedSongs, onLyricsBatch, policy ->
            scanFolder(
                treeUri = treeUri,
                cachedSongs = cachedSongs,
                onProgress = onProgress,
                onLyricsBatch = onLyricsBatch,
                policy = policy,
            )
        }
    }

    private fun scheduleAutoArtworkHydration(addedIds: Set<String>) {
        if (addedIds.isEmpty()) return
        val targets = addedIds.filterTo(linkedSetOf()) { songId ->
            val song = backing.songById(songId) ?: return@filterTo false
            song.albumArtUri.isNullOrBlank()
        }
        if (targets.isEmpty()) return

        DiagnosticLog.event(
            "LibraryAutoSync",
            "artwork-hydrate queued targets=${targets.size} ids=" +
                targets.take(4).joinToString(",") { it.takeLast(12) },
        )
        backing.syncScheduler.submit(
            LibraryOperationRequest.TargetedRefresh(
                songIds = targets,
                cause = LibraryOperationCause.AUTO_ARTWORK_HYDRATE,
            ),
        )
    }

    fun launchRefreshSongMetadata(songId: String) {
        if (songId.isBlank()) return
        backing.syncScheduler.submit(LibraryOperationRequest.TargetedRefresh(setOf(songId)))
    }

    suspend fun refreshSongMetadata(songId: String) {
        refreshSongMetadata(setOf(songId), operation = null)
    }

    private suspend fun refreshSongMetadata(
        songIds: Set<String>,
        operation: ScheduledLibraryOperation?,
    ) {
        val targets = songIds.filterTo(linkedSetOf()) { id ->
            id.isNotBlank() && backing.songById(id) != null
        }
        if (targets.isEmpty()) return
        when (backing.lastScanSource) {
            ScanSource.FOLDER -> if (folder.hasLibraryFolder()) {
                scanLibraryFolder(
                    forceRefreshSongIds = targets,
                    operation = operation,
                )
            }
            ScanSource.DEVICE -> if (folder.hasAudioReadPermission()) {
                scanDeviceWide(
                    forceRefreshSongIds = targets,
                    operation = operation,
                )
            }
        }
    }

    private suspend fun scanDevice(
        cachedSongs: List<com.mica.music.data.Song>,
        onProgress: (Int, Int) -> Unit,
        onLyricsBatch: suspend (com.mica.music.data.LyricsScanBatch) -> Unit,
        policy: ScanProbePolicy,
    ): ScanResult = if (policy.forceRefreshSongIds.isEmpty()) {
        backing.libraryScanner.scanDevice(
            cachedSongs = cachedSongs,
            onProgress = onProgress,
            forceRefreshLyrics = policy.forceRefreshLyrics,
            forceRefreshArtwork = false,
            onLyricsBatch = onLyricsBatch,
        )
    } else {
        backing.libraryScanner.scanDeviceForSongs(
            songIds = policy.forceRefreshSongIds,
            cachedSongs = cachedSongs,
            onProgress = onProgress,
            forceRefreshLyrics = policy.forceRefreshLyrics,
            forceRefreshArtwork = false,
            onLyricsBatch = onLyricsBatch,
        )
    }

    private suspend fun scanFolder(
        treeUri: android.net.Uri,
        cachedSongs: List<com.mica.music.data.Song>,
        onProgress: (Int, Int) -> Unit,
        onLyricsBatch: suspend (com.mica.music.data.LyricsScanBatch) -> Unit,
        policy: ScanProbePolicy,
    ): ScanResult = if (policy.forceRefreshSongIds.isEmpty()) {
        backing.libraryScanner.scanFolder(
            treeUri = treeUri,
            cachedSongs = cachedSongs,
            onProgress = onProgress,
            forceRefreshLyrics = policy.forceRefreshLyrics,
            forceRefreshArtwork = false,
            onLyricsBatch = onLyricsBatch,
        )
    } else {
        backing.libraryScanner.scanFolderForSongs(
            treeUri = treeUri,
            songIds = policy.forceRefreshSongIds,
            cachedSongs = cachedSongs,
            onProgress = onProgress,
            forceRefreshLyrics = policy.forceRefreshLyrics,
            forceRefreshArtwork = false,
            onLyricsBatch = onLyricsBatch,
        )
    }

    private suspend fun repairArtworkCache(
        plan: AlbumArtRepairPlan,
        operation: ScheduledLibraryOperation? = null,
    ) {
        when (plan.action) {
            AlbumArtRepairAction.ScanDevice -> repairDeviceArtwork(operation)
            AlbumArtRepairAction.ScanFolder -> repairLibraryFolderArtwork(operation)
            AlbumArtRepairAction.NoReadableSource -> Unit
        }
    }

    private suspend fun repairDeviceArtwork(operation: ScheduledLibraryOperation?) {
        performScan(
            source = ScanSource.DEVICE,
            requestedForceRefreshLyrics = false,
            userVisible = false,
            operation = operation,
        ) {
                onProgress, cachedSongs, onLyricsBatch, policy ->
            backing.libraryScanner.scanDevice(
                cachedSongs = cachedSongs,
                onProgress = onProgress,
                forceRefreshLyrics = policy.forceRefreshLyrics,
                forceRefreshArtwork = true,
                onLyricsBatch = onLyricsBatch,
            )
        }
    }

    private suspend fun repairLibraryFolderArtwork(operation: ScheduledLibraryOperation?) {
        val uriString = backing.libraryFolderUri ?: return
        val treeUri = uriString.toUri()
        if (!backing.scanEnvironment.canReadTree(treeUri)) {
            DiagnosticLog.important("AlbumArtCache", "repair-folder-skip cannot-read-tree uri=$treeUri")
            return
        }
        performScan(
            source = ScanSource.FOLDER,
            requestedForceRefreshLyrics = false,
            userVisible = false,
            operation = operation,
        ) {
                onProgress, cachedSongs, onLyricsBatch, policy ->
            backing.libraryScanner.scanFolder(
                treeUri = treeUri,
                cachedSongs = cachedSongs,
                onProgress = onProgress,
                forceRefreshLyrics = policy.forceRefreshLyrics,
                forceRefreshArtwork = true,
                onLyricsBatch = onLyricsBatch,
            )
        }
    }

    private suspend fun performScan(
        source: ScanSource,
        requestedForceRefreshLyrics: Boolean,
        forceRefreshSongIds: Set<String> = emptySet(),
        userVisible: Boolean = true,
        operation: ScheduledLibraryOperation? = null,
        block: suspend (
            onProgress: (Int, Int) -> Unit,
            cachedSongs: List<com.mica.music.data.Song>,
            onLyricsBatch: suspend (com.mica.music.data.LyricsScanBatch) -> Unit,
            policy: ScanProbePolicy,
        ) -> ScanResult,
    ) = backing.operationExecutionMutex.withLock {
        performScanLocked(
            source = source,
            requestedForceRefreshLyrics = requestedForceRefreshLyrics,
            forceRefreshSongIds = forceRefreshSongIds,
            userVisible = userVisible,
            operation = operation,
            block = block,
        )
    }

    private suspend fun performScanLocked(
        source: ScanSource,
        requestedForceRefreshLyrics: Boolean,
        forceRefreshSongIds: Set<String>,
        userVisible: Boolean,
        operation: ScheduledLibraryOperation?,
        block: suspend (
            onProgress: (Int, Int) -> Unit,
            cachedSongs: List<com.mica.music.data.Song>,
            onLyricsBatch: suspend (com.mica.music.data.LyricsScanBatch) -> Unit,
            policy: ScanProbePolicy,
        ) -> ScanResult,
    ) {
        if (backing.released || backing.releaseRequested) return
        val token = backing.beginOperationToken(
            source = source,
            requestSequence = operation?.requestSequence ?: 0L,
            dirtySequenceAtStart = operation?.dirtySequenceAtStart ?: backing.syncScheduler.dirtySequence,
            mode = operation?.request?.mode ?: if (forceRefreshSongIds.isEmpty()) {
                LibraryOperationMode.FULL
            } else {
                LibraryOperationMode.TARGETED_REFRESH
            },
            cause = operation?.request?.cause ?: LibraryOperationCause.USER_RESCAN,
        ) ?: return
        val generation = token.libraryGeneration
        val sideEffects = LibraryScanSideEffectPolicy.forMode(token.mode)
        val deviceFullAnchorConfigKey = if (
            source == ScanSource.DEVICE &&
            token.mode == LibraryOperationMode.FULL &&
            forceRefreshSongIds.isEmpty()
        ) {
            deviceShadowConfigKey(token.configFingerprint, token.activationEpoch)
        } else {
            null
        }
        val deviceFullScanAnchor = deviceFullAnchorConfigKey?.let { configKey ->
            withContext(backing.ioDispatcher) {
                backing.deviceAutoSyncShadow.captureFullScanAnchor(configKey)
            }
        }
        val stagedLyricsId = backing.operationStagingId(token)
            .takeIf { backing.isPendingSourceOperation(token) }
        var published = false
        val scanStartedMs = SystemClock.elapsedRealtime()
        val scanStartCatalogSnapshot =
            catalog.scannedSongsSnapshot().takeIf { it.isNotEmpty() } ?: backing.songs
        val metadataRefreshLibrarySnapshot = if (forceRefreshSongIds.isEmpty()) {
            emptyList()
        } else {
            catalog.scannedSongsSnapshot().takeIf { it.isNotEmpty() } ?: backing.songs
        }
        DiagnosticLog.event(
            "LibraryScan",
            "performScan start source=$source generation=$generation " +
                "request=${token.requestSequence} dirtyAtStart=${token.dirtySequenceAtStart} " +
                "activation=${token.activationEpoch} sourceIdentity=${token.sourceIdentity.storageKey()} " +
                "currentSongs=${backing.songs.size} targetRefresh=${forceRefreshSongIds.size}",
        )
        backing.isScanning = true
        backing.isUserVisibleScanning = userVisible
        if (userVisible) {
            backing.lastScanError = null
            backing.scanProgressLabel = "姝ｅ湪璇诲彇姝屾洸鍒楄〃鈥?
        }
        if (sideEffects.clearTransientScanCache) {
            backing.scanEnvironment.clearTransientCache()
        }
        try {
            val cacheStartedMs = SystemClock.elapsedRealtime()
            val cachedSongs = if (catalog.hasScannedSongs()) {
                catalog.scannedSongsSnapshot()
            } else {
                withContext(backing.ioDispatcher) {
                    backing.libraryStore.loadCached()?.songs.orEmpty()
                }
            }
            DiagnosticLog.event(
                "LibraryScan",
                "performScan cachedSongs durMs=${SystemClock.elapsedRealtime() - cacheStartedMs} " +
                    "songs=${cachedSongs.size} generation=$generation",
            )
            val lyricsParserUpgrade =
                sideEffects.ownsGlobalLyricsMaintenance &&
                    backing.scanEnvironment.lyricsParserVersion() < CURRENT_LYRICS_PARSER_VERSION
            val globalLyricsRetry =
                sideEffects.ownsGlobalLyricsMaintenance &&
                    backing.scanEnvironment.lyricsRetryRequired()
            val policy = ScanProbePolicy(
                forceRefreshLyrics = requestedForceRefreshLyrics || lyricsParserUpgrade || globalLyricsRetry,
                forceRefreshSongIds = forceRefreshSongIds,
            )
            val result = block(
                { done, total ->
                    if (userVisible && backing.isCurrentOperationToken(token)) {
                        backing.scanProgressLabel = "姝ｅ湪鍒嗘瀽闊宠川銆佸皝闈笌姝岃瘝 ($done/$total)"
                    }
                },
                cachedSongs,
                { batch ->
                    val committed = backing.storeWriteIfCurrentOperation(token) {
                        if (batch.readFailedCount > 0 && sideEffects.ownsGlobalLyricsMaintenance) {
                            backing.scanEnvironment.persistLyricsRetryRequired(true)
                        }
                        if (stagedLyricsId != null) {
                            backing.libraryStore.stageLyrics(stagedLyricsId, batch.completed)
                        } else {
                            backing.libraryStore.applyLyricsBatch(batch.completed)
                        }
                    }
                    if (committed && stagedLyricsId == null) {
                        SharedLyricsMemoryCache.invalidateSongs(batch.completed.map { it.songId })
                    }
                },
                policy,
            )
            DiagnosticLog.event(
                "LibraryScan",
                "performScan scannerResult durMs=${SystemClock.elapsedRealtime() - scanStartedMs} " +
                    "songs=${result.songs.size} generation=$generation " +
                    "technicalFailed=${result.probeStats.technicalFailed}",
            )
            if (!backing.isCurrentOperationToken(token)) return
            val excludedObjectKeys = withContext(backing.ioDispatcher) {
                backing.libraryStore.loadUserExclusions(token.sourceIdentity)
                    .mapTo(linkedSetOf()) { it.stableObjectKey }
            }
            if (!backing.isCurrentOperationToken(token)) return
            val scannerSongsAfterUserExclusions = result.songs.filterNot { song ->
                userExclusionStableObjectKey(song) in excludedObjectKeys
            }
            val songsForPublish = mergeMetadataRefreshIntoSnapshot(
                scannedSongs = scannerSongsAfterUserExclusions,
                previousSongs = metadataRefreshLibrarySnapshot,
                targetSongIds = forceRefreshSongIds,
            )
            val totalSizeMbForPublish = if (forceRefreshSongIds.isEmpty()) {
                result.totalSizeMb
            } else {
                (songsForPublish.sumOf { it.sizeBytes.coerceAtLeast(0L) } / (1024L * 1024L)).toInt()
            }
            val lyricsReadFailed = result.probeStats.hasLyricsReadFailures()
            if (lyricsReadFailed && sideEffects.ownsGlobalLyricsMaintenance) {
                backing.scanEnvironment.persistLyricsRetryRequired(true)
            }
            val scanAtMs = backing.scanEnvironment.currentTimeMillis()
            val deviceFullCheckpointMutation =
                (deviceFullScanAnchor as? DeviceFullScanShadowAnchor.Available)?.let { anchor ->
                    val existingCheckpoints = withContext(backing.ioDispatcher) {
                        backing.libraryStore.loadSyncCheckpoints(token.sourceIdentity)
                    }
                    if (!backing.isCurrentOperationToken(token)) return
                    DeviceGenerationCheckpointCodec.replacementMutation(
                        sourceIdentity = token.sourceIdentity,
                        snapshot = anchor.snapshot,
                        configFingerprint = token.configFingerprint,
                        committedAtMs = scanAtMs,
                        existingCheckpoints = existingCheckpoints,
                    )
                }
            val deviceAnchorAdopt = deviceFullAnchorConfigKey?.let { configKey ->
                val anchor = requireNotNull(deviceFullScanAnchor)
                val adopt: () -> Unit = {
                    backing.deviceAutoSyncShadow.acceptFullScanAnchor(
                        anchor = anchor,
                        configKey = configKey,
                    )
                }
                adopt
            }
            if (publishSongs(
                    raw = songsForPublish,
                    token = token,
                    source = source,
                    scanAtMs = scanAtMs,
                    totalSizeMb = totalSizeMbForPublish,
                    stagedLyricsId = stagedLyricsId,
                    scanStartCatalog = scanStartCatalogSnapshot,
                    sideEffects = sideEffects,
                    autoSyncStateMutation = deviceFullCheckpointMutation,
                    afterStoreCommitAdopt = deviceAnchorAdopt,
                ) == null
            ) {
                return
            }
            published = true
            if (
                deviceFullScanAnchor != null &&
                backing.isCurrentOperationToken(token)
            ) {
                DiagnosticLog.event(
                    "LibraryAutoSync",
                    "device full-anchor accepted request=${token.requestSequence} " +
                        "generation=$generation anchor=$deviceFullScanAnchor",
                )
                if (deviceFullScanAnchor is DeviceFullScanShadowAnchor.Available) {
                    val canonicalSnapshot = DeviceShadowCanonicalCatalog.snapshot(
                        songs = backing.songs,
                        context = DeviceShadowCanonicalContext(
                            sourceIdentityStorageKey = token.sourceIdentity.storageKey(),
                            activationEpoch = token.activationEpoch,
                            configFingerprint = token.configFingerprint,
                            providerIdentityDomain =
                                deviceFullScanAnchor.snapshot.providerIdentityDomainKey(),
                        ),
                    )
                    val canonicalResult =
                        deviceShadowCanonicalCoverage.acceptFullSnapshot(canonicalSnapshot)
                    logDeviceShadowCanonicalCoverage(
                        requestSequence = token.requestSequence,
                        result = canonicalResult,
                    )
                    val projectionResult =
                        deviceShadowCanonicalProjection.acceptFullSnapshot(canonicalSnapshot)
                    logDeviceShadowCanonicalProjection(
                        requestSequence = token.requestSequence,
                        result = projectionResult,
                    )
                }
            }
            if (source == ScanSource.FOLDER && backing.isCurrentOperationToken(token)) {
                val safCanonicalSnapshot = SafShadowCanonicalCatalog.snapshot(
                    songs = backing.songs,
                    context = SafShadowCanonicalContext(
                        sourceIdentityStorageKey = token.sourceIdentity.storageKey(),
                        activationEpoch = token.activationEpoch,
                        configFingerprint = token.configFingerprint,
                    ),
                )
                logSafShadowCanonicalProjection(
                    requestSequence = token.requestSequence,
                    result = safShadowCanonicalProjection.acceptFullSnapshot(safCanonicalSnapshot),
                )
                safShadowVideoInventory.seed(result.folderVideoFiles)
                folder.commitPendingFolderIfActivated(token)
                if (sideEffects.prefetchVideoCoverPosters) {
                    backing.scanEnvironment.enqueueVideoCoverPosterPrefetch(
                        songsForPublish.mapNotNull { song ->
                            val uri = song.videoCoverUri ?: return@mapNotNull null
                            com.mica.music.data.scanner.VideoCoverPosterRef(
                                uri = uri,
                                revision = song.videoCoverRevision,
                            )
                        },
                    )
                }
            }
            if (
                sideEffects.ownsGlobalLyricsMaintenance &&
                !lyricsReadFailed &&
                backing.isCurrentOperationToken(token)
            ) {
                if (lyricsParserUpgrade) {
                    backing.scanEnvironment.persistLyricsParserVersion(CURRENT_LYRICS_PARSER_VERSION)
                    backing.lyricsDataVersion = CURRENT_LYRICS_PARSER_VERSION
                }
                backing.scanEnvironment.persistLyricsRetryRequired(false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!backing.isCurrentOperationToken(token)) return
            // Keep the previous complete snapshot; only user-visible operations surface the error.
            if (userVisible) {
                backing.lastScanError = e.message?.takeIf { it.isNotBlank() } ?: "鏈煡閿欒"
            }
            DiagnosticLog.event("LibraryScan", "performScan failed generation=$generation", e)
        } finally {
            withContext(NonCancellable) {
                stagedLyricsId?.let { backing.discardOperationStaging(it) }
                if (!published) {
                    backing.abandonPendingTransition(token)
                    folder.discardPendingFolderIfMatches(token)
                }
            }
            // Source cleanup may intentionally make the token non-current. Generation
            // ownership is the correct guard for resetting this operation's transient scan state:
            // a newer operation/clear has a newer generation and must not be clobbered.
            if (backing.isActiveGeneration(generation)) {
                backing.isScanning = false
                backing.isUserVisibleScanning = false
                if (userVisible) backing.scanProgressLabel = null
                DiagnosticLog.event(
                    "LibraryScan",
                    "performScan end durMs=${SystemClock.elapsedRealtime() - scanStartedMs} " +
                        "generation=$generation songs=${backing.songs.size} error=${backing.lastScanError != null}",
                )
            }
        }
    }

    private fun mergeMetadataRefreshIntoSnapshot(
        scannedSongs: List<Song>,
        previousSongs: List<Song>,
        targetSongIds: Set<String>,
    ): List<Song> {
        if (targetSongIds.isEmpty() || previousSongs.isEmpty()) return scannedSongs
        val scannedById = scannedSongs.associateBy(Song::id)
        return previousSongs.map { previous ->
            if (previous.id !in targetSongIds) return@map previous
            val fresh = scannedById[previous.id] ?: return@map previous
            fresh.copy(
                mediaUri = previous.mediaUri,
                fileName = previous.fileName,
                folderPath = previous.folderPath,
                filePath = previous.filePath,
                dateAddedMs = previous.dateAddedMs,
            )
        }
    }

    private suspend fun publishSongs(
        raw: List<com.mica.music.data.Song>,
        token: LibraryOperationToken,
        source: ScanSource,
        scanAtMs: Long,
        totalSizeMb: Int,
        stagedLyricsId: String?,
        scanStartCatalog: List<com.mica.music.data.Song>,
        sideEffects: LibraryScanSideEffectPolicy,
        autoSyncStateMutation: LibraryAutoSyncStateMutation? = null,
        afterStoreCommitAdopt: (() -> Unit)? = null,
    ): com.mica.music.data.local.LibrarySyncResult? {
        val generation = token.libraryGeneration
        val persistedScanAtMs = if (sideEffects.publishUserScanMetadata) {
            scanAtMs
        } else {
            backing.lastScanAtMs ?: scanAtMs
        }
        val persistedScanSource = if (sideEffects.publishUserScanMetadata) {
            source
        } else {
            backing.lastScanSource ?: source
        }
        repeat(MAX_PUBLICATION_REBASE_ATTEMPTS) { attempt ->
            if (!backing.isCurrentOperationToken(token)) return null
            val field = backing.sortField
            val direction = backing.sortDirection
            val publicationRaw = rebaseScanResultForCurrentCatalog(
                scanned = raw,
                scanStartCatalog = scanStartCatalog,
                currentCatalog = catalog.scannedSongsSnapshot().takeIf { it.isNotEmpty() } ?: backing.songs,
                catalogChanged = backing.catalogRevision != token.catalogRevisionAtStart,
            )
            val prepared = catalog.prepareLibrarySongs(
                raw = publicationRaw,
                field = field,
                direction = direction,
                diagnosticTag = "LibraryScan",
                diagnosticReason = "scanPublish",
                releaseLoadedLyrics = true,
            )
            val syncStartedMs = SystemClock.elapsedRealtime()
            val sync = backing.commitSnapshotAndPublishIfCurrent(
                token = token,
                expectedCatalogRevision = prepared.catalogRevision,
                expectedPresentationRevision = prepared.presentationRevision,
                storeBlock = {
                    backing.libraryStore.commitScanAuthority(
                        songs = prepared.visible,
                        lastScanAtMs = persistedScanAtMs,
                        lastScanSource = persistedScanSource,
                        totalSizeMb = totalSizeMb,
                        state = backing.persistedStateAfterActivation(token),
                        autoSyncStateMutation = autoSyncStateMutation,
                        stagedLyricsId = stagedLyricsId,
                        sortField = field,
                        sortDirection = direction,
                        fastScrollSectionTargets = prepared.fastScrollIndex?.sectionTargets,
                    )
                },
                publishBlock = { committed ->
                    val previousPublished = backing.songs
                    backing.activateOperationSourceAfterFinalCommit(token)
                    catalog.adoptPrepared(prepared)
                    afterStoreCommitAdopt?.invoke()
                    publishChangeSet(previousPublished, prepared.visible, token)
                    backing.totalSizeMb = totalSizeMb
                    backing.hasScanned = true
                    if (sideEffects.publishUserScanMetadata) {
                        backing.lastScanAtMs = scanAtMs
                        backing.lastScanSource = source
                        backing.lastScanError = null
                        backing.scanEnvironment.persistLastScanSource(source)
                        backing.lastScanSyncSummary = committed.toSummary()
                    }
                    catalog.persistPreparedCustomOrderIfCurrent(prepared)
                },
            )
            if (sync != null) {
                DiagnosticLog.event(
                    "LibraryScan",
                    "publishSongs dbSync durMs=${SystemClock.elapsedRealtime() - syncStartedMs} " +
                        "generation=$generation visible=${prepared.visible.size} attempt=$attempt",
                )
                if (sideEffects.runAlbumArtMaintenance) {
                    backing.launchAlbumArtCacheMaintenance()
                }
                return sync
            }
            if (!backing.isCurrentOperationToken(token)) return null
            DiagnosticLog.event(
                "LibraryScan",
                "publishSongs rebase-retry generation=$generation attempt=$attempt " +
                    "catalogRevision=${backing.catalogRevision} " +
                    "presentationRevision=${backing.presentationRevision}",
            )
        }
        DiagnosticLog.important(
            "LibraryScan",
            "publishSongs rebase-exhausted generation=$generation attempts=$MAX_PUBLICATION_REBASE_ATTEMPTS",
        )
        return null
    }

    private fun publishChangeSet(
        previous: List<com.mica.music.data.Song>,
        current: List<com.mica.music.data.Song>,
        token: LibraryOperationToken,
    ) {
        val previousById = previous.associateBy(com.mica.music.data.Song::id)
        val currentById = current.associateBy(com.mica.music.data.Song::id)
        val addedIds = currentById.keys - previousById.keys
        val updatedIds = (currentById.keys intersect previousById.keys)
            .filterTo(linkedSetOf()) { id -> previousById[id] != currentById[id] }

        // S0 deliberately does not infer removal reasons. S1 discovery produces evidence-backed
        // MembershipChange values (CONFIRMED_MISSING/FILTERED_OUT/etc.) before destructive AUTO.
        if (addedIds.isEmpty() && updatedIds.isEmpty() && previousById.keys == currentById.keys) {
            return
        }
        val revision = ++backing.libraryChangeRevision
        backing.lastLibraryChangeSet = LibraryChangeSet(
            libraryRevision = revision,
            cause = token.cause,
            addedIds = addedIds,
            updatedIds = updatedIds,
            membershipChanges = emptyList(),
        )
    }

    private companion object {
        const val DEVICE_REQUERY_RETRY_DELAY_MS = 30_000L
        const val SAF_PROVIDER_RESELECT_FAILURE_THRESHOLD = 3
    }
}
