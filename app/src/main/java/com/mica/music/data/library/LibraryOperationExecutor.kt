package com.mica.music.data.library
import com.mica.music.data.AlbumArtRepairPlan
import com.mica.music.data.ScanSource
import com.mica.music.data.Song
import com.mica.music.data.scanner.AutoSyncVisibleDelta
internal class LibraryOperationExecutor(
    private val backing: MusicLibraryBacking,
) {
    private val catalog get() = backing.catalog
    private val folder get() = backing.folder
    private val deviceShadowCanonicalCoverage = DeviceShadowCanonicalCoverageTracker()
    private val deviceShadowCanonicalProjection = DeviceShadowCanonicalProjectionTracker()
    private val safShadowCanonicalProjection = SafShadowCanonicalProjectionTracker()
    private val safShadowVideoInventory = SafShadowVideoInventoryTracker()
    private val autoSyncExecutor = LibraryAutoSyncExecutor(
        backing = backing,
        deviceShadowCanonicalCoverage = deviceShadowCanonicalCoverage,
        deviceShadowCanonicalProjection = deviceShadowCanonicalProjection,
        safShadowCanonicalProjection = safShadowCanonicalProjection,
        safShadowVideoInventory = safShadowVideoInventory,
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
    private val artworkRepairExecutor = ArtworkRepairExecutor(
        backing = backing,
        scanEngine = scanEngine,
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

    internal fun resetSafProviderDiscoveryBackoff() =
        autoSyncExecutor.resetSafProviderDiscoveryBackoff()

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
                artworkRepairExecutor.repair(request.plan, operation)
            is LibraryOperationRequest.AutoSync ->
                autoSyncExecutor.executeScheduled(operation)
        }
    }

    internal suspend fun executeAutoSyncShadowForDiagnostics(
        operation: ScheduledLibraryOperation,
    ) = autoSyncExecutor.executeShadowForDiagnostics(operation)

    internal suspend fun executeAutoSyncForReadiness(
        operation: ScheduledLibraryOperation,
    ) = autoSyncExecutor.executeForReadiness(operation)

    internal suspend fun seedSafShadowCanonicalForDiagnostics() =
        autoSyncExecutor.seedSafShadowCanonicalForDiagnostics()

    /** Compatibility seam while readiness tests still target the orchestrator directly. */
    internal suspend fun publishDeviceAutoSyncPlanForReadiness(
        token: LibraryOperationToken,
        scanStartSnapshot: List<Song>,
        plan: DeviceAutoSyncPublicationPlan,
    ): com.mica.music.data.local.LibrarySyncResult? =
        autoSyncExecutor.publishDevicePlanForReadiness(token, scanStartSnapshot, plan)

    /** Compatibility seam while readiness tests still target the orchestrator directly. */
    internal suspend fun publishSafAutoSyncPlanForReadiness(
        token: LibraryOperationToken,
        scanStartSnapshot: List<Song>,
        plan: SafAutoSyncPublicationPlan,
    ): com.mica.music.data.local.LibrarySyncResult? =
        autoSyncExecutor.publishSafPlanForReadiness(token, scanStartSnapshot, plan)

    /** Compatibility seam for publication atomicity tests. */
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
        autoSyncExecutor.publishSnapshot(
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

    fun launchRefreshSongMetadata(songId: String) {
        if (songId.isBlank()) return
        backing.syncScheduler.submit(LibraryOperationRequest.TargetedRefresh(setOf(songId)))
    }

    suspend fun refreshSongMetadata(songId: String) {
        targetedMetadataRefreshExecutor.refresh(setOf(songId))
    }




}

// Temporary test compatibility while readiness tests migrate to the new executor name.
internal typealias LibraryScanOrchestrator = LibraryOperationExecutor
