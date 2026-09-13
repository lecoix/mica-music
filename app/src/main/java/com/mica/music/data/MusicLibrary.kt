package com.mica.music.data

import android.content.Context
import android.net.Uri
import com.mica.music.data.library.AndroidDeviceShadowProbeRuntime
import com.mica.music.data.library.AndroidDeviceRetryObservationRuntime
import com.mica.music.data.library.DeviceRetryObservationRuntime
import com.mica.music.data.library.SafShadowProbeRuntime
import com.mica.music.data.library.NoopSafShadowProbeRuntime
import com.mica.music.data.library.AndroidSafShadowProbeRuntime
import com.mica.music.data.library.DeviceShadowProbeRuntime
import com.mica.music.data.library.LibraryFollowupOutboxItem
import com.mica.music.data.library.LibraryUserExclusion
import com.mica.music.data.library.NoopDeviceShadowProbeRuntime
import com.mica.music.data.library.NoopDeviceRetryObservationRuntime
import com.mica.music.data.library.MusicLibraryBacking
import com.mica.music.data.library.userExclusionStableObjectKey
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.data.scanner.AndroidDeviceAutoSyncShadow
import com.mica.music.data.scanner.CoverColorPersistence
import com.mica.music.data.scanner.DeviceAutoSyncShadow
import com.mica.music.data.scanner.NoopDeviceAutoSyncShadow
import com.mica.music.data.scanner.canPersistCoverColor
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class StartupBrowseTarget {
    NONE,
    ARTISTS,
    ALBUMS,
}

class MusicLibrary internal constructor(
    context: Context,
    libraryScanner: LibraryScanner,
    libraryStore: LibraryStore,
    scanEnvironment: ScanEnvironment,
    mainDispatcher: CoroutineDispatcher,
    ioDispatcher: CoroutineDispatcher,
    deviceAutoSyncShadow: DeviceAutoSyncShadow = NoopDeviceAutoSyncShadow,
    deviceShadowProbeRuntime: DeviceShadowProbeRuntime = NoopDeviceShadowProbeRuntime,
    deviceRetryObservationRuntime: DeviceRetryObservationRuntime =
        NoopDeviceRetryObservationRuntime,
    safShadowProbeRuntime: SafShadowProbeRuntime = NoopSafShadowProbeRuntime,
    private val ownsCoverColorPersistenceSink: Boolean = false,
) {
    private val backing = MusicLibraryBacking(
        context = context,
        libraryScanner = libraryScanner,
        libraryStore = libraryStore,
        scanEnvironment = scanEnvironment,
        mainDispatcher = mainDispatcher,
        ioDispatcher = ioDispatcher,
        deviceAutoSyncShadow = deviceAutoSyncShadow,
        deviceShadowProbeRuntime = deviceShadowProbeRuntime,
        deviceRetryObservationRuntime = deviceRetryObservationRuntime,
        safShadowProbeRuntime = safShadowProbeRuntime,
    )

    constructor(context: Context) : this(
        context = context,
        libraryScanner = AndroidLibraryScanner(context),
        libraryStore = RoomLibraryStore(context),
        scanEnvironment = AndroidScanEnvironment(context),
        mainDispatcher = Dispatchers.Main.immediate,
        ioDispatcher = Dispatchers.IO,
        deviceAutoSyncShadow = AndroidDeviceAutoSyncShadow(context),
        deviceShadowProbeRuntime = AndroidDeviceShadowProbeRuntime(context),
        deviceRetryObservationRuntime = AndroidDeviceRetryObservationRuntime(context),
        safShadowProbeRuntime = AndroidSafShadowProbeRuntime(context),
        ownsCoverColorPersistenceSink = true,
    )

    private val coverColorPersistenceSink = CoverColorPersistence.Sink { songId, albumArtUri, argb ->
        applyCoverColorArgb(songId, albumArtUri, argb)
    }

    val songs get() = backing.songs

    /** 可见曲库 id 序列；仅顺序/成员变化时更新，供播放队列同步 LaunchedEffect 使用。 */
    val songIds get() = backing.songIds

    val queueMetadataRevision get() = backing.queueMetadataRevision

    internal val libraryChangeRevision get() = backing.libraryChangeRevision

    internal val lastLibraryChangeSet get() = backing.lastLibraryChangeSet

    val lyricsDataVersion get() = backing.lyricsDataVersion

    val artistSplitRevision get() = backing.browse.artistSplitRevision

    val sortField get() = backing.sortField

    val sortDirection get() = backing.sortDirection

    val customSongOrderLocked get() = backing.customSongOrderLocked

    val isLoadingCachedLibrary get() = backing.isLoadingCachedLibrary

    val isScanning get() = backing.isScanning

    val isUserVisibleScanning get() = backing.isUserVisibleScanning

    val hasScanned get() = backing.hasScanned

    val totalSizeMb get() = backing.totalSizeMb

    val lastScanAtMs get() = backing.lastScanAtMs

    val permissionGranted get() = backing.permissionGranted

    val libraryFolderUri get() = backing.libraryFolderUri

    val libraryFolderLabel get() = backing.libraryFolderLabel

    val scanLibraryFolderLabel get() = backing.folder.displayFolderLabel()

    val lastScanSource get() = backing.lastScanSource

    val lastScanError get() = backing.lastScanError

    val lastScanSyncSummary get() = backing.lastScanSyncSummary

    val scanProgressLabel get() = backing.scanProgressLabel

    val songFastScrollLabels get() = backing.songFastScrollLabels

    val songFastScrollSectionTargets get() = backing.songFastScrollSectionTargets

    init {
        if (ownsCoverColorPersistenceSink) {
            CoverColorPersistence.attach(coverColorPersistenceSink)
        }
        backing.folder.reloadLibraryFolderFromPrefs()
        backing.catalog.reloadSortFromPrefs()
        backing.lastScanSource = LibraryScanSettings.lastScanSource(context)
    }

    fun updateSort(field: SongSortField, direction: SortDirection) =
        backing.catalog.updateSort(field, direction)

    fun moveSongInLibrary(fromIndex: Int, toIndex: Int): Boolean =
        backing.catalog.moveVisibleSong(fromIndex, toIndex)

    fun updateCustomSongOrderLocked(locked: Boolean) =
        backing.catalog.updateCustomSongOrderLocked(locked)

    fun updateArtistSplitConfig(config: ArtistSplitConfig) {
        backing.browse.updateArtistSplitConfig(config)
    }

    /** Refreshes in-memory song presentation after process-lifetime stats persistence. */
    fun applyPlayStats(songId: String, stats: PlayStats) {
        if (backing.released) return
        backing.catalog.applyPlayStats(songId, stats)
    }

    fun applyLoudnessAnalysis(
        songId: String,
        analysis: LoudnessAnalysis,
        notifyQueueMetadata: Boolean = true,
    ) {
        if (backing.released) return
        backing.catalog.applyLoudnessAnalysis(songId, analysis, notifyQueueMetadata)
    }

    fun applyCoverColorArgb(songId: String, albumArtUri: String?, argb: Int) {
        if (backing.released) return
        backing.catalog.applyCoverColorArgb(songId, albumArtUri, argb)
        persistCoverColorAsync(songId, albumArtUri, argb)
    }

    private fun persistCoverColorAsync(songId: String, albumArtUri: String?, argb: Int) {
        backing.ioScope.launch {
            backing.storeWriteIfCurrentObjectState(
                isCurrent = {
                    canPersistCoverColor(backing.songById(songId), songId, albumArtUri, argb)
                },
            ) {
                backing.libraryStore.updateCoverColorArgb(songId, argb)
            }
        }
    }

    fun notifyLoudnessScanCompleted() {
        if (backing.released) return
        backing.catalog.notifyQueueMetadataChanged()
    }

    fun searchSongs(query: String): List<Song> = backing.browse.searchSongs(query)

    fun songById(id: String): Song? = backing.songById(id)

    suspend fun songWithLyrics(
        song: Song,
        priority: List<LyricsSlot> = DEFAULT_LYRICS_SLOT_PRIORITY,
        isPrefetch: Boolean = false,
    ): Song = backing.lyricsHydrator.hydrate(song, priority, isPrefetch)

    fun prefetchLyrics(
        song: Song?,
        priority: List<LyricsSlot> = DEFAULT_LYRICS_SLOT_PRIORITY,
    ) = backing.lyricsHydrator.prefetch(song, priority)

    /**
     * 从曲库移除并持久化 user exclusion。Room authority 成功后才发布内存，避免 AUTO/FULL
     * 在重启后重新发现同一对象。
     */
    suspend fun removeSongFromLibrary(
        song: Song,
        persistExclusion: Boolean = true,
    ): Boolean {
        if (backing.released) return false
        val sourceIdentity = backing.sourceState.active?.sourceIdentity ?: return false
        val lastScanAtMs = backing.lastScanAtMs ?: return false
        val stableObjectKey = userExclusionStableObjectKey(song)

        repeat(USER_EXCLUSION_REBASE_ATTEMPTS) {
            val catalogRevision = backing.catalogRevision
            val presentationRevision = backing.presentationRevision
            val currentScanned = backing.catalog.scannedSongsSnapshot()
            if (currentScanned.none { it.id == song.id }) return true

            val prepared = backing.catalog.prepareLibrarySongs(
                raw = currentScanned.filterNot { it.id == song.id },
                field = backing.sortField,
                direction = backing.sortDirection,
                diagnosticTag = "LibraryMutation",
                diagnosticReason = if (persistExclusion) "user-exclusion" else "physical-delete",
            )
            val createdAtMs = backing.scanEnvironment.currentTimeMillis()
            val exclusion = if (persistExclusion) {
                val existingExclusions = backing.libraryStore.loadUserExclusions(sourceIdentity)
                val exclusionRevision = maxOf(
                    createdAtMs,
                    (existingExclusions.maxOfOrNull { it.exclusionRevision } ?: 0L) + 1L,
                )
                LibraryUserExclusion(
                    sourceIdentity = sourceIdentity,
                    stableObjectKey = stableObjectKey,
                    exclusionRevision = exclusionRevision,
                    createdAtMs = createdAtMs,
                )
            } else {
                null
            }
            val remainingTotalSizeMb =
                (prepared.scanned.sumOf { it.sizeBytes.coerceAtLeast(0L) } / (1024L * 1024L)).toInt()

            val committed = backing.replaceSnapshotAuthority(
                expectedCatalogRevision = catalogRevision,
                expectedPresentationRevision = presentationRevision,
                expectedSourceIdentity = sourceIdentity,
                storeBlock = {
                    if (exclusion != null) {
                        backing.libraryStore.commitUserExclusionAuthority(
                            songs = prepared.scanned,
                            lastScanAtMs = lastScanAtMs,
                            lastScanSource = sourceIdentity.source,
                            totalSizeMb = remainingTotalSizeMb,
                            exclusion = exclusion,
                            sortField = backing.sortField,
                            sortDirection = backing.sortDirection,
                            fastScrollSectionTargets = prepared.fastScrollIndex?.sectionTargets,
                        )
                    } else {
                        backing.libraryStore.commitScan(
                            songs = prepared.scanned,
                            lastScanAtMs = lastScanAtMs,
                            lastScanSource = sourceIdentity.source,
                            totalSizeMb = remainingTotalSizeMb,
                            sortField = backing.sortField,
                            sortDirection = backing.sortDirection,
                            fastScrollSectionTargets = prepared.fastScrollIndex?.sectionTargets,
                        )
                    }
                },
                publishBlock = { _, _ ->
                    backing.catalog.adoptPrepared(prepared)
                    backing.catalog.persistPreparedCustomOrderIfCurrent(prepared)
                    backing.totalSizeMb = remainingTotalSizeMb
                    val revision = ++backing.libraryChangeRevision
                    backing.lastLibraryChangeSet = com.mica.music.data.library.LibraryChangeSet(
                        libraryRevision = revision,
                        cause = com.mica.music.data.library.LibraryOperationCause.LOCAL_USER_DELETE,
                        addedIds = emptySet(),
                        updatedIds = emptySet(),
                        membershipChanges = listOf(
                            com.mica.music.data.library.MembershipChange(
                                stableObjectKey = stableObjectKey,
                                songId = song.id,
                                reason = if (exclusion != null) {
                                    com.mica.music.data.library.MembershipRemovalReason.USER_EXCLUDED
                                } else {
                                    com.mica.music.data.library.MembershipRemovalReason.CONFIRMED_MISSING
                                },
                                evidenceRevision = exclusion?.exclusionRevision?.toString()
                                    ?: "local-physical-delete:$createdAtMs",
                                sourceIdentity = sourceIdentity,
                            ),
                        ),
                    )
                },
            )
            if (committed != null) return true
        }
        return false
    }

    private companion object {
        const val USER_EXCLUSION_REBASE_ATTEMPTS = 3
    }

    internal suspend fun loadFollowupOutboxPage(
        cursor: com.mica.music.data.library.LibraryFollowupOutboxCursor,
        limit: Int,
    ): com.mica.music.data.library.LibraryFollowupOutboxPage =
        withContext(Dispatchers.IO) {
            backing.libraryStore.loadFollowupOutboxPage(cursor, limit)
        }

    internal suspend fun acknowledgeFollowupOutbox(eventId: String): Boolean =
        withContext(Dispatchers.IO) {
            backing.libraryStore.acknowledgeFollowupOutbox(eventId)
        }

    internal suspend fun consumeConfirmedMissingFollowup(
        playlistStore: PlaylistStore,
        item: LibraryFollowupOutboxItem,
        songId: String,
    ): Boolean {
        val commit = backing.publicationAuthority.withPublicationGate {
            playlistStore.commitConfirmedMissingFollowup(item, songId)
        } ?: return false
        return playlistStore.publishConfirmedMissingFollowup(commit)
    }

    fun recentSongs(): List<Song> = backing.browse.recentSongs()

    fun artistGroups(): List<BrowseGroup> = backing.browse.artistGroups()

    fun albumGroups(): List<BrowseGroup> = backing.browse.albumGroups()

    fun artistGroupPresentation(
        field: ArtistBrowseSortField,
        direction: SortDirection,
    ): BrowseGroupPresentation = backing.browse.artistGroupPresentation(field, direction)

    fun albumGroupPresentation(
        field: AlbumBrowseSortField,
        direction: SortDirection,
    ): BrowseGroupPresentation = backing.browse.albumGroupPresentation(field, direction)

    suspend fun prewarmBrowseGroupCache() = backing.browse.prewarmBrowseGroupCache()

    fun folderGroups(pathSegments: List<String> = emptyList()): List<FolderBrowseGroup> =
        backing.browse.folderGroups(pathSegments)

    fun folderGroupsAtDepth(depth: Int, scopePathSegments: List<String> = emptyList()): List<FolderBrowseGroup> =
        backing.browse.folderGroupsAtDepth(depth, scopePathSegments)

    fun musicFolderGroups(): List<FolderBrowseGroup> = backing.browse.musicFolderGroups()

    fun maxFolderDepth(): Int = backing.browse.maxFolderDepth()

    fun songsForArtist(artist: String): List<Song> = backing.browse.songsForArtist(artist)

    fun songsForAlbum(albumKey: AlbumBrowseKey): List<Song> =
        backing.browse.songsForAlbum(albumKey)

    fun songsForFolder(pathSegments: List<String>): List<Song> =
        backing.browse.songsForFolder(pathSegments)

    fun songsInFolder(pathSegments: List<String>): List<Song> =
        backing.browse.songsInFolder(pathSegments)

    fun reloadLibraryFolderFromPrefs() = backing.folder.reloadLibraryFolderFromPrefs()

    fun hasLibraryFolder(): Boolean = backing.folder.hasLibraryFolder()

    fun setLibraryFolder(treeUri: Uri) = backing.folder.setLibraryFolder(treeUri)

    fun clearLibraryFolder() = backing.folder.clearLibraryFolder()

    fun updatePermission(granted: Boolean) = backing.folder.updatePermission(granted)

    fun audioReadPermission(): String = backing.folder.audioReadPermission()

    fun hasAudioReadPermission(): Boolean = backing.folder.hasAudioReadPermission()

    fun clearLibrary() = backing.folder.clearLibrary()

    /** 启动时从 Room 恢复上次扫描结果，避免每次冷启动都要重扫。 */
    suspend fun loadCachedLibrary(target: StartupBrowseTarget = StartupBrowseTarget.NONE) {
        val cachedBrowse = backing.cacheLoader.loadCachedLibrary(target) ?: return
        backing.browse.adoptCachedBrowse(cachedBrowse)
    }

    /** Schedules non-blocking album-art maintenance against the committed library snapshot. */
    fun launchAlbumArtCacheMaintenance() {
        if (backing.released) return
        backing.launchAlbumArtCacheMaintenance()
    }

    suspend fun rescan() = backing.operationExecutor.rescan()

    suspend fun scan() = backing.operationExecutor.scan()

    /**
     * 扫描器内部切到 IO；状态编排保留在主线程，避免跨线程写 Compose State。
     */
    fun launchRescan() = backing.operationExecutor.launchRescan()

    fun launchScanDeviceWide() = backing.operationExecutor.launchScanDeviceWide()

    fun launchScanLibraryFolder() = backing.operationExecutor.launchScanLibraryFolder()

    /** 封面修复：协调器决策计划，扫描编排器执行。 */
    fun launchArtworkCacheRepairIfNeeded(reason: String = "startup") {
        if (backing.released || backing.isScanning || backing.songs.isEmpty()) return
        val plan = AlbumArtRepairCoordinator.plan(
            context = backing.context,
            songs = backing.songs,
            lastScanSource = backing.lastScanSource,
            hasLibraryFolder = backing.folder.hasLibraryFolder(),
            hasAudioReadPermission = backing.folder.hasAudioReadPermission(),
            reason = reason,
        ) ?: return
        if (plan.action == AlbumArtRepairAction.NoReadableSource) return
        backing.operationExecutor.launchArtworkCacheRepair(plan)
    }

    suspend fun scanDeviceWide() = backing.operationExecutor.scanDeviceWide()

    suspend fun scanLibraryFolder() = backing.operationExecutor.scanLibraryFolder()

    /**
     * Internal diagnostics seam used only by debug/QA controls.
     *
     * This executes the existing shadow orchestrator without publishing AUTO state. Production
     * dirty signals still enter exclusively through [LibrarySyncScheduler].
     */
    internal suspend fun seedSafShadowCanonicalForDiagnostics() =
        backing.operationExecutor.seedSafShadowCanonicalForDiagnostics()

    internal suspend fun runAutoSyncShadowForDiagnostics(
        cause: com.mica.music.data.library.LibraryOperationCause =
            com.mica.music.data.library.LibraryOperationCause.SAF_PERIODIC_VERIFY,
        requestSequence: Long = android.os.SystemClock.elapsedRealtime(),
        publishSafAuthority: Boolean = false,
    ) {
        val operation = com.mica.music.data.library.ScheduledLibraryOperation(
            request = com.mica.music.data.library.LibraryOperationRequest.AutoSync(cause),
            requestSequence = requestSequence,
            dirtySequenceAtStart = backing.syncScheduler.dirtySequence,
        )
        if (publishSafAuthority) {
            backing.operationExecutor.executeAutoSyncForReadiness(operation)
        } else {
            backing.operationExecutor.executeAutoSyncShadowForDiagnostics(operation)
        }
    }

    fun launchRefreshSongMetadata(songId: String) =
        backing.operationExecutor.launchRefreshSongMetadata(songId)

    fun clearScanSyncSummary() {
        backing.lastScanSyncSummary = null
    }

    fun onForegroundChanged(inForeground: Boolean) {
        backing.isAutoSyncForeground = inForeground
        backing.dirtySignalObserver.onForegroundChanged(inForeground)
    }

    internal fun setPlaybackIoSnapshotProvider(
        provider: () -> com.mica.music.data.library.LibraryPlaybackIoSnapshot,
    ) = backing.setPlaybackIoSnapshotProvider(provider)

    internal fun onPlaybackIoLeaseChanged() = backing.onPlaybackIoLeaseChanged()

    fun release() {
        if (ownsCoverColorPersistenceSink) {
            CoverColorPersistence.detach(coverColorPersistenceSink)
        }
        backing.release()
    }
}
