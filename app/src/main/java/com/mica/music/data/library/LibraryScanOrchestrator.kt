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
    private val catalog get() = backing.catalog
    private val folder get() = backing.folder
    private val deviceShadowCanonicalCoverage = DeviceShadowCanonicalCoverageTracker()
    private val deviceShadowCanonicalProjection = DeviceShadowCanonicalProjectionTracker()
    private val safShadowCanonicalProjection = SafShadowCanonicalProjectionTracker()
    private val safShadowVideoInventory = SafShadowVideoInventoryTracker()
    private val safProviderDiscoveryBackoff = SafProviderDiscoveryBackoff()
    private val autoSyncPublicationAuthority = AutoSyncPublicationAuthority(backing)
    private val deviceAutoSyncPipeline = DeviceAutoSyncPipeline(
        backing = backing,
        publicationAuthority = autoSyncPublicationAuthority,
        canonicalCoverage = deviceShadowCanonicalCoverage,
        canonicalProjection = deviceShadowCanonicalProjection,
    )
    private val safAutoSyncPipeline = SafAutoSyncPipeline(
        backing = backing,
        publicationAuthority = autoSyncPublicationAuthority,
        safShadowCanonicalProjection = safShadowCanonicalProjection,
        safShadowVideoInventory = safShadowVideoInventory,
        safProviderDiscoveryBackoff = safProviderDiscoveryBackoff,
    )
    private val scanEngine = LibraryScanEngine(
        backing = backing,
        deviceShadowCanonicalCoverage = deviceShadowCanonicalCoverage,
        deviceShadowCanonicalProjection = deviceShadowCanonicalProjection,
        safShadowCanonicalProjection = safShadowCanonicalProjection,
        safShadowVideoInventory = safShadowVideoInventory,
    )
    private val fullScanExecutor = FullLibraryScanExecutor(
        backing = backing,
        scanEngine = scanEngine,
    )
    private val targetedMetadataRefreshExecutor = TargetedMetadataRefreshExecutor(
        backing = backing,
        fullScanExecutor = fullScanExecutor,
    )

    suspend fun rescan() = rescan(null)

    private suspend fun rescan(operation: ScheduledLibraryOperation?) {
        when (backing.lastScanSource) {
            ScanSource.FOLDER -> {
                if (folder.hasLibraryFolder()) {
                    fullScanExecutor.scanLibraryFolder(operation = operation)
                }
            }
            ScanSource.DEVICE -> {
                if (folder.hasAudioReadPermission()) {
                    fullScanExecutor.scanDeviceWide(operation = operation)
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
            LibraryOperationRequest.ScanDeviceWide -> fullScanExecutor.scanDeviceWide(operation = operation)
            LibraryOperationRequest.ScanLibraryFolder -> fullScanExecutor.scanLibraryFolder(operation = operation)
            is LibraryOperationRequest.TargetedRefresh ->
                targetedMetadataRefreshExecutor.refresh(request.songIds, operation)
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
            val postCommit = safAutoSyncPipeline.execute(
                operation = operation,
                token = token,
                scheduleBudgetContinuation = scheduleSafBudgetContinuation,
                publishAuthority = publishSafAuthority,
            )
            applyAutoSyncPostCommit(postCommit)
            return
        }
        if (activeSource != ScanSource.DEVICE) return

        val postCommit = deviceAutoSyncPipeline.execute(
            operation = operation,
            token = token,
            publishAuthority = publishDeviceAuthority,
        )
        applyAutoSyncPostCommit(postCommit)
    }

    private fun applyAutoSyncPostCommit(postCommit: AutoSyncPostCommit) {
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


    suspend fun scanDeviceWide(
        forceRefreshSongIds: Set<String> = emptySet(),
        userVisible: Boolean = forceRefreshSongIds.isEmpty(),
        operation: ScheduledLibraryOperation? = null,
    ) = fullScanExecutor.scanDeviceWide(
        forceRefreshSongIds = forceRefreshSongIds,
        userVisible = userVisible,
        operation = operation,
    )

    suspend fun scanLibraryFolder(
        forceRefreshSongIds: Set<String> = emptySet(),
        userVisible: Boolean = forceRefreshSongIds.isEmpty(),
        operation: ScheduledLibraryOperation? = null,
    ) = fullScanExecutor.scanLibraryFolder(
        forceRefreshSongIds = forceRefreshSongIds,
        userVisible = userVisible,
        operation = operation,
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

    fun launchRefreshSongMetadata(songId: String) {
        if (songId.isBlank()) return
        backing.syncScheduler.submit(LibraryOperationRequest.TargetedRefresh(setOf(songId)))
    }

    suspend fun refreshSongMetadata(songId: String) {
        targetedMetadataRefreshExecutor.refresh(setOf(songId))
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
        scanEngine.performScan(
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
        scanEngine.performScan(
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


}
