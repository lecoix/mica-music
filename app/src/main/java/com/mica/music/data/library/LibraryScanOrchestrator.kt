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
                backing.lastScanError = "无法访问所选文件夹，请重新选择"
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
            backing.scanProgressLabel = "正在读取歌曲列表…"
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
                        backing.scanProgressLabel = "正在分析音质、封面与歌词 ($done/$total)"
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
                backing.lastScanError = e.message?.takeIf { it.isNotBlank() } ?: "未知错误"
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
    }
}
