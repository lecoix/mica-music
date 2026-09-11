package com.mica.music.data.library

import com.mica.music.data.ScanSource
import com.mica.music.data.Song
import com.mica.music.data.scanner.AutoSyncVisibleDelta
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.sync.withLock

/** Owns AUTO execution and post-commit scheduling while DEVICE/SAF pipelines stay scheduler-free. */
internal class LibraryAutoSyncExecutor(
    private val backing: MusicLibraryBacking,
    private val deviceShadowCanonicalCoverage: DeviceShadowCanonicalCoverageTracker,
    private val deviceShadowCanonicalProjection: DeviceShadowCanonicalProjectionTracker,
    private val safShadowCanonicalProjection: SafShadowCanonicalProjectionTracker,
    private val safShadowVideoInventory: SafShadowVideoInventoryTracker,
) {
    private val safProviderDiscoveryBackoff = SafProviderDiscoveryBackoff()
    private val publicationAuthority = AutoSyncPublicationAuthority(backing)
    private val devicePipeline = DeviceAutoSyncPipeline(
        backing = backing,
        publicationAuthority = publicationAuthority,
        canonicalCoverage = deviceShadowCanonicalCoverage,
        canonicalProjection = deviceShadowCanonicalProjection,
    )
    private val safPipeline = SafAutoSyncPipeline(
        backing = backing,
        publicationAuthority = publicationAuthority,
        safShadowCanonicalProjection = safShadowCanonicalProjection,
        safShadowVideoInventory = safShadowVideoInventory,
        safProviderDiscoveryBackoff = safProviderDiscoveryBackoff,
    )

    fun resetSafProviderDiscoveryBackoff() {
        safProviderDiscoveryBackoff.reset()
    }

    suspend fun executeScheduled(operation: ScheduledLibraryOperation) {
        execute(
            operation = operation,
            scheduleSafBudgetContinuation = true,
            publishSafAuthority = true,
            publishDeviceAuthority = true,
            enforceAutoSyncGate = true,
        )
    }

    suspend fun executeShadowForDiagnostics(operation: ScheduledLibraryOperation) {
        execute(
            operation = operation,
            scheduleSafBudgetContinuation = false,
            publishSafAuthority = false,
            publishDeviceAuthority = false,
            enforceAutoSyncGate = false,
        )
    }

    suspend fun executeForReadiness(operation: ScheduledLibraryOperation) {
        execute(
            operation = operation,
            scheduleSafBudgetContinuation = false,
            publishSafAuthority = true,
            publishDeviceAuthority = true,
            enforceAutoSyncGate = false,
        )
    }

    private suspend fun execute(
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

        when (token.sourceIdentity.source) {
            ScanSource.FOLDER -> applyPostCommit(
                safPipeline.execute(
                    operation = operation,
                    token = token,
                    scheduleBudgetContinuation = scheduleSafBudgetContinuation,
                    publishAuthority = publishSafAuthority,
                ),
            )
            ScanSource.DEVICE -> applyPostCommit(
                devicePipeline.execute(
                    operation = operation,
                    token = token,
                    publishAuthority = publishDeviceAuthority,
                ),
            )
        }
    }

    private fun applyPostCommit(postCommit: AutoSyncPostCommit) {
        postCommit.actions.forEach { action ->
            when (action) {
                is AutoSyncPostCommitAction.RetryWake ->
                    backing.syncScheduler.replaceAutoRetryWake(
                        cause = action.cause,
                        delayMs = action.delayMs,
                        sourceIdentity = action.sourceIdentity,
                        activationEpoch = action.activationEpoch,
                    )

                is AutoSyncPostCommitAction.DirtyFollowUp ->
                    backing.syncScheduler.markDirty(action.cause)

                is AutoSyncPostCommitAction.AutoContinuation ->
                    backing.syncScheduler.requestAutoContinuation(action.cause)

                is AutoSyncPostCommitAction.ArtworkHydration ->
                    scheduleAutoArtworkHydration(action.songIds)
            }
        }
    }

    suspend fun seedSafShadowCanonicalForDiagnostics() {
        val stamp = backing.captureShadowObservationStamp(ScanSource.FOLDER)
            ?: error("No active FOLDER source available for SAF diagnostics baseline")
        val snapshot = SafShadowCanonicalCatalog.snapshot(
            songs = backing.songs,
            context = SafShadowCanonicalContext(
                sourceIdentityStorageKey = stamp.sourceActivation.sourceIdentity.storageKey(),
                activationEpoch = stamp.sourceActivation.activationEpoch,
                configFingerprint = stamp.configFingerprint,
            ),
        )
        logSafShadowCanonicalProjection(
            requestSequence = 0L,
            result = safShadowCanonicalProjection.acceptFullSnapshot(snapshot),
        )
        safShadowVideoInventory.seed(emptyList())
    }

    suspend fun publishDevicePlanForReadiness(
        token: LibraryOperationToken,
        scanStartSnapshot: List<Song>,
        plan: DeviceAutoSyncPublicationPlan,
    ): com.mica.music.data.local.LibrarySyncResult? =
        publicationAuthority.publishDevicePlan(token, scanStartSnapshot, plan)

    suspend fun publishSafPlanForReadiness(
        token: LibraryOperationToken,
        scanStartSnapshot: List<Song>,
        plan: SafAutoSyncPublicationPlan,
    ): com.mica.music.data.local.LibrarySyncResult? =
        publicationAuthority.publishSafPlan(token, scanStartSnapshot, plan)

    suspend fun publishSnapshot(
        token: LibraryOperationToken,
        scanStartSnapshot: List<Song>,
        nextSnapshot: List<Song>,
        visibleDelta: AutoSyncVisibleDelta,
        membershipChanges: List<MembershipChange>,
        autoSyncStateMutation: LibraryAutoSyncStateMutation,
        stagedLyricsId: String? = null,
        stagedExternalLyricsId: String? = null,
    ): com.mica.music.data.local.LibrarySyncResult? =
        publicationAuthority.publishSnapshot(
            token = token,
            scanStartSnapshot = scanStartSnapshot,
            nextSnapshot = nextSnapshot,
            visibleDelta = visibleDelta,
            membershipChanges = membershipChanges,
            autoSyncStateMutation = autoSyncStateMutation,
            stagedLyricsId = stagedLyricsId,
            stagedExternalLyricsId = stagedExternalLyricsId,
        )

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
}