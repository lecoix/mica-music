package com.mica.music.data

import android.content.Context
import android.net.Uri
import com.mica.music.data.DEFAULT_LYRICS_SLOT_PRIORITY
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.data.preferences.PlaybackUiPreferences
import com.mica.music.data.local.CachedLibrary
import com.mica.music.data.local.LibraryRepository
import com.mica.music.data.local.LibrarySyncResult
import com.mica.music.data.library.LibraryAutoSyncStateMutation
import com.mica.music.data.library.LibraryFollowupOutboxCursor
import com.mica.music.data.library.LibraryFollowupOutboxItem
import com.mica.music.data.library.LibraryFollowupOutboxPage
import com.mica.music.data.library.LibraryRetryCursor
import com.mica.music.data.library.LibraryRetryItem
import com.mica.music.data.library.LibraryRetryKind
import com.mica.music.data.library.LibraryRetryPage
import com.mica.music.data.library.LibraryRetryPaging
import com.mica.music.data.library.LibrarySyncCheckpoint
import com.mica.music.data.library.LyricsStagingMode
import com.mica.music.data.library.LibraryUserExclusion
import com.mica.music.data.library.PersistedLibraryState
import com.mica.music.data.library.SourceIdentityKey
import com.mica.music.data.scanner.FolderScanner
import com.mica.music.data.scanner.MediaStoreScanner
import com.mica.music.data.scanner.ScanCacheManager
import com.mica.music.data.scanner.ScanResult
import com.mica.music.data.scanner.DiscoveryReport
import com.mica.music.data.scanner.DiscoveryPartitions
import com.mica.music.data.scanner.DiscoveryPartitionStatus
import com.mica.music.data.scanner.DiscoveryCompleteness
import com.mica.music.data.scanner.SafTreeMetadataSnapshot
import com.mica.music.data.scanner.VideoCoverPosterPrefetcher

internal interface LibraryScanner {
    suspend fun scanDevice(
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean = false,
        forceRefreshArtwork: Boolean = false,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)? = null,
    ): ScanResult

    suspend fun scanDeviceForSongs(
        songIds: Set<String>,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean = false,
        forceRefreshArtwork: Boolean = false,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)? = null,
    ): ScanResult = scanDevice(
        cachedSongs = cachedSongs,
        onProgress = onProgress,
        forceRefreshLyrics = forceRefreshLyrics,
        forceRefreshArtwork = forceRefreshArtwork,
        onLyricsBatch = onLyricsBatch,
    )

    suspend fun observeFolderMetadata(
        treeUri: Uri,
    ): SafTreeMetadataSnapshot = SafTreeMetadataSnapshot(
        entries = emptyList(),
        discoveryReport = DiscoveryReport.of(
            DiscoveryPartitionStatus(
                partitionKey = DiscoveryPartitions.SAF_TREE,
                completeness = DiscoveryCompleteness.UNAVAILABLE,
                detail = "folder-metadata-observer-not-implemented",
            ),
        ),
    )

    suspend fun scanFolder(
        treeUri: Uri,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean = false,
        forceRefreshArtwork: Boolean = false,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)? = null,
    ): ScanResult

    suspend fun scanFolderForSongs(
        treeUri: Uri,
        songIds: Set<String>,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean = false,
        forceRefreshArtwork: Boolean = false,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)? = null,
    ): ScanResult = scanFolder(
        treeUri = treeUri,
        cachedSongs = cachedSongs,
        onProgress = onProgress,
        forceRefreshLyrics = forceRefreshLyrics,
        forceRefreshArtwork = forceRefreshArtwork,
        onLyricsBatch = onLyricsBatch,
    )
}

internal data class LibraryAutoSyncStoreRow(
    val song: Song,
    val queueOrderHint: Int,
)

internal data class LibraryAutoSyncStoreDelta(
    val upsertRows: List<LibraryAutoSyncStoreRow>,
    val removedSongIds: List<String>,
    val snapshotSongCount: Int,
    val addedCount: Int,
    val updatedCount: Int,
) {
    init {
        require(snapshotSongCount >= 0)
        require(addedCount >= 0)
        require(updatedCount >= 0)
        require(upsertRows.map(LibraryAutoSyncStoreRow::song).map(Song::id).distinct().size ==
            upsertRows.size)
        require(removedSongIds.distinct().size == removedSongIds.size)
        require(upsertRows.none { it.song.id in removedSongIds })
    }

    val removedCount: Int
        get() = removedSongIds.size

    val unchangedCount: Int
        get() = (snapshotSongCount - addedCount - updatedCount).coerceAtLeast(0)
}

internal interface LibraryStore {
    suspend fun loadCached(): CachedLibrary?

    suspend fun loadLibraryState(): PersistedLibraryState? = null

    suspend fun saveLibraryState(state: PersistedLibraryState) = Unit

    suspend fun loadSyncCheckpoints(sourceIdentity: SourceIdentityKey): List<LibrarySyncCheckpoint> =
        emptyList()

    suspend fun loadRetryItemsPage(
        sourceIdentity: SourceIdentityKey,
        cursor: LibraryRetryCursor,
        limit: Int,
    ): LibraryRetryPage {
        require(limit in 1..LibraryRetryPaging.PAGE_SIZE)
        return LibraryRetryPage(emptyList(), null)
    }

    suspend fun loadDueRetryItems(
        sourceIdentity: SourceIdentityKey,
        retryKind: LibraryRetryKind,
        activationEpoch: Long,
        nowMs: Long,
        limit: Int,
    ): List<LibraryRetryItem> {
        require(limit in 1..LibraryRetryPaging.DUE_WORK_BUDGET)
        return emptyList()
    }

    suspend fun loadRetryItemsForStableObjectKeys(
        sourceIdentity: SourceIdentityKey,
        stableObjectKeys: Collection<String>,
    ): List<LibraryRetryItem> {
        if (stableObjectKeys.isEmpty()) return emptyList()
        require(stableObjectKeys.size <= LibraryRetryPaging.STABLE_KEY_LOOKUP_BATCH_SIZE)
        return emptyList()
    }

    suspend fun loadNextRetryAtMsAfter(
        sourceIdentity: SourceIdentityKey,
        activationEpoch: Long,
        afterMs: Long,
    ): Long? = null

    suspend fun applyAutoSyncState(mutation: LibraryAutoSyncStateMutation) = Unit

    suspend fun loadUserExclusions(sourceIdentity: SourceIdentityKey): List<LibraryUserExclusion> =
        emptyList()

    suspend fun upsertUserExclusion(exclusion: LibraryUserExclusion) = Unit

    suspend fun removeUserExclusion(sourceIdentity: SourceIdentityKey, stableObjectKey: String) = Unit

    /**
     * User-exclusion authority mutation. Production stores must persist the exclusion and the
     * resulting complete snapshot in one transaction before memory publication.
     */
    suspend fun commitUserExclusionAuthority(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        exclusion: LibraryUserExclusion,
        sortField: SongSortField? = null,
        sortDirection: SortDirection? = null,
        fastScrollSectionTargets: Map<String, Int>? = null,
    ): LibrarySyncResult {
        upsertUserExclusion(exclusion)
        return commitScan(
            songs = songs,
            lastScanAtMs = lastScanAtMs,
            lastScanSource = lastScanSource,
            totalSizeMb = totalSizeMb,
            sortField = sortField,
            sortDirection = sortDirection,
            fastScrollSectionTargets = fastScrollSectionTargets,
        )
    }

    suspend fun loadFollowupOutboxPage(
        cursor: LibraryFollowupOutboxCursor,
        limit: Int,
    ): LibraryFollowupOutboxPage {
        require(limit > 0)
        return LibraryFollowupOutboxPage(emptyList(), null)
    }

    suspend fun enqueueFollowupOutbox(item: LibraryFollowupOutboxItem) = Unit

    suspend fun acknowledgeFollowupOutbox(eventId: String): Boolean = true

    suspend fun loadLyrics(
        songId: String,
        revision: String,
        priority: List<LyricsSlot> = DEFAULT_LYRICS_SLOT_PRIORITY,
    ): LyricsDocument = LyricsDocument()

    suspend fun applyLyricsBatch(batch: List<ScannedSongLyrics>) = Unit

    suspend fun stageLyrics(scanId: String, batch: List<ScannedSongLyrics>) =
        applyLyricsBatch(batch)

    suspend fun discardStagedLyrics(scanId: String) = Unit

    suspend fun save(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        sortField: SongSortField? = null,
        sortDirection: SortDirection? = null,
        fastScrollSectionTargets: Map<String, Int>? = null,
    ): LibrarySyncResult

    suspend fun syncIncremental(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        sortField: SongSortField? = null,
        sortDirection: SortDirection? = null,
        fastScrollSectionTargets: Map<String, Int>? = null,
    ): LibrarySyncResult

    suspend fun commitScan(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        sortField: SongSortField? = null,
        sortDirection: SortDirection? = null,
        fastScrollSectionTargets: Map<String, Int>? = null,
    ): LibrarySyncResult = syncIncremental(
        songs,
        lastScanAtMs,
        lastScanSource,
        totalSizeMb,
        sortField,
        sortDirection,
        fastScrollSectionTargets,
    )

    suspend fun commitScanAuthority(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        state: PersistedLibraryState,
        autoSyncStateMutation: LibraryAutoSyncStateMutation? = null,
        stagedLyricsId: String? = null,
        sortField: SongSortField? = null,
        sortDirection: SortDirection? = null,
        fastScrollSectionTargets: Map<String, Int>? = null,
    ): LibrarySyncResult {
        val result = commitScan(
            songs = songs,
            lastScanAtMs = lastScanAtMs,
            lastScanSource = lastScanSource,
            totalSizeMb = totalSizeMb,
            sortField = sortField,
            sortDirection = sortDirection,
            fastScrollSectionTargets = fastScrollSectionTargets,
        )
        saveLibraryState(state)
        autoSyncStateMutation?.let { applyAutoSyncState(it) }
        return result
    }

    /**
     * Visible AUTO publication authority. Production stores must commit the complete next
     * snapshot, auto-sync checkpoint/retry mutation and durable followups atomically.
     */
    suspend fun commitAutoSyncSnapshotAuthority(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        state: PersistedLibraryState,
        autoSyncStateMutation: LibraryAutoSyncStateMutation,
        followupOutboxItems: List<LibraryFollowupOutboxItem>,
        stagedLyricsId: String? = null,
        stagedLyricsMode: LyricsStagingMode = LyricsStagingMode.FULL_REPLACE,
        stagedExternalLyricsId: String? = null,
        sortField: SongSortField? = null,
        sortDirection: SortDirection? = null,
        fastScrollSectionTargets: Map<String, Int>? = null,
    ): LibrarySyncResult {
        val result = commitScanAuthorityWithFollowups(
            songs = songs,
            lastScanAtMs = lastScanAtMs,
            lastScanSource = lastScanSource,
            totalSizeMb = totalSizeMb,
            state = state,
            stagedLyricsId = stagedLyricsId,
            followupOutboxItems = followupOutboxItems,
            sortField = sortField,
            sortDirection = sortDirection,
            fastScrollSectionTargets = fastScrollSectionTargets,
        )
        applyAutoSyncState(autoSyncStateMutation)
        return result
    }

    /**
     * Visible AUTO fast path. Production Room persists only the canonical rows touched by the
     * prepared delta and atomically invalidates derived presentation/browse caches. Non-Room test
     * stores intentionally fall back to the complete-snapshot seam.
     */
    suspend fun commitAutoSyncDeltaAuthority(
        snapshotSongs: List<Song>,
        delta: LibraryAutoSyncStoreDelta,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        state: PersistedLibraryState,
        autoSyncStateMutation: LibraryAutoSyncStateMutation,
        followupOutboxItems: List<LibraryFollowupOutboxItem>,
        stagedLyricsId: String? = null,
        stagedLyricsMode: LyricsStagingMode = LyricsStagingMode.FULL_REPLACE,
        stagedExternalLyricsId: String? = null,
        sortField: SongSortField? = null,
        sortDirection: SortDirection? = null,
        fastScrollSectionTargets: Map<String, Int>? = null,
    ): LibrarySyncResult = commitAutoSyncSnapshotAuthority(
        songs = snapshotSongs,
        lastScanAtMs = lastScanAtMs,
        lastScanSource = lastScanSource,
        totalSizeMb = totalSizeMb,
        state = state,
        autoSyncStateMutation = autoSyncStateMutation,
        followupOutboxItems = followupOutboxItems,
        stagedLyricsId = stagedLyricsId,
        stagedLyricsMode = stagedLyricsMode,
        stagedExternalLyricsId = stagedExternalLyricsId,
        sortField = sortField,
        sortDirection = sortDirection,
        fastScrollSectionTargets = fastScrollSectionTargets,
    )

    suspend fun commitScanAuthorityWithFollowups(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        state: PersistedLibraryState,
        stagedLyricsId: String? = null,
        followupOutboxItems: List<LibraryFollowupOutboxItem>,
        sortField: SongSortField? = null,
        sortDirection: SortDirection? = null,
        fastScrollSectionTargets: Map<String, Int>? = null,
    ): LibrarySyncResult {
        val result = commitScanAuthority(
            songs = songs,
            lastScanAtMs = lastScanAtMs,
            lastScanSource = lastScanSource,
            totalSizeMb = totalSizeMb,
            state = state,
            stagedLyricsId = stagedLyricsId,
            sortField = sortField,
            sortDirection = sortDirection,
            fastScrollSectionTargets = fastScrollSectionTargets,
        )
        followupOutboxItems.forEach { enqueueFollowupOutbox(it) }
        return result
    }

    suspend fun clearAuthority(state: PersistedLibraryState) {
        clear()
        saveLibraryState(state)
    }

    suspend fun updatePresentation(
        songIds: List<String>,
        sortField: SongSortField,
        sortDirection: SortDirection,
        fastScrollSectionTargets: Map<String, Int>?,
    ) = Unit

    suspend fun updateCoverColorArgb(songId: String, coverColorArgb: Int) = Unit

    suspend fun updateBrowseGroups(
        artistGroups: List<BrowseGroup>,
        albumGroups: List<BrowseGroup>,
        artistConfigKey: String,
        artistSortField: ArtistBrowseSortField,
        artistSortDirection: SortDirection,
        artistFastScrollSectionTargets: Map<String, Int>?,
        albumSortField: AlbumBrowseSortField,
        albumSortDirection: SortDirection,
        albumFastScrollSectionTargets: Map<String, Int>?,
    ) = Unit

    suspend fun clear()
}

internal const val CURRENT_LYRICS_PARSER_VERSION = 12

internal interface ScanEnvironment {
    fun hasAudioReadPermission(): Boolean
    fun canReadTree(treeUri: Uri): Boolean
    /**
     * Persisted grant presence is only a recovery diagnostic. It never makes discovery COMPLETE.
     */
    fun hasPersistedTreeReadAccess(treeUri: Uri): Boolean = false
    /**
     * Best-effort provider reacquire used only after [canReadTree] already failed.
     * A false result remains fail-closed and must never trigger direct-file fallback.
     */
    fun canAcquireTreeProvider(treeUri: Uri): Boolean = true
    fun currentTimeMillis(): Long
    fun elapsedRealtimeMillis(): Long = android.os.SystemClock.elapsedRealtime()
    fun playStats(songId: String): PlayStats
    fun playStatsSnapshot(songIds: Collection<String>): PlayStatsSnapshot =
        PlayStatsSnapshot.from(songIds.associateWith { songId -> playStats(songId) })
    fun clearTransientCache()
    /** Background cache maintenance against a snapshot that has already been committed. */
    fun pruneAlbumArtCache(songs: List<Song>)
    /** Folder-scan only: background first-frame posters for matched video covers. */
    fun enqueueVideoCoverPosterPrefetch(videoCoverRefs: Collection<com.mica.music.data.scanner.VideoCoverPosterRef>) = Unit
    fun persistLastScanSource(source: ScanSource)
    fun lyricsParserVersion(): Int = CURRENT_LYRICS_PARSER_VERSION
    fun persistLyricsParserVersion(version: Int) = Unit
    fun lyricsRetryRequired(): Boolean = false
    fun persistLyricsRetryRequired(required: Boolean) = Unit
}

internal class AndroidLibraryScanner(
    private val context: Context,
) : LibraryScanner {
    override suspend fun scanDevice(
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean,
        forceRefreshArtwork: Boolean,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
    ): ScanResult = MediaStoreScanner.scan(
        context = context,
        options = LibraryScanSettings.scanOptions(context).copy(
            forceRefreshLyrics = forceRefreshLyrics,
            forceRefreshArtwork = forceRefreshArtwork,
        ),
        cachedSongs = cachedSongs,
        onProgress = onProgress,
        onLyricsBatch = onLyricsBatch,
    )

    override suspend fun scanDeviceForSongs(
        songIds: Set<String>,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean,
        forceRefreshArtwork: Boolean,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
    ): ScanResult = MediaStoreScanner.scan(
        context = context,
        options = LibraryScanSettings.scanOptions(context).copy(
            forceRefreshLyrics = forceRefreshLyrics,
            forceRefreshArtwork = forceRefreshArtwork,
            forceRefreshSongIds = songIds,
            scanOnlySongIds = songIds,
        ),
        cachedSongs = cachedSongs,
        onProgress = onProgress,
        onLyricsBatch = onLyricsBatch,
    )

    override suspend fun observeFolderMetadata(
        treeUri: Uri,
    ): SafTreeMetadataSnapshot = FolderScanner.observeMetadata(
        context = context,
        treeUri = treeUri,
        options = LibraryScanSettings.scanOptions(context),
    )

    override suspend fun scanFolder(
        treeUri: Uri,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean,
        forceRefreshArtwork: Boolean,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
    ): ScanResult = FolderScanner.scan(
        context = context,
        treeUri = treeUri,
        options = LibraryScanSettings.scanOptions(context).copy(
            forceRefreshLyrics = forceRefreshLyrics,
            forceRefreshArtwork = forceRefreshArtwork,
        ),
        cachedSongs = cachedSongs,
        onProgress = onProgress,
        onLyricsBatch = onLyricsBatch,
    )

    override suspend fun scanFolderForSongs(
        treeUri: Uri,
        songIds: Set<String>,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean,
        forceRefreshArtwork: Boolean,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
    ): ScanResult = FolderScanner.scan(
        context = context,
        treeUri = treeUri,
        options = LibraryScanSettings.scanOptions(context).copy(
            forceRefreshLyrics = forceRefreshLyrics,
            forceRefreshArtwork = forceRefreshArtwork,
            forceRefreshSongIds = songIds,
            scanOnlySongIds = songIds,
        ),
        cachedSongs = cachedSongs,
        onProgress = onProgress,
        onLyricsBatch = onLyricsBatch,
    )
}

internal class RoomLibraryStore internal constructor(
    private val repository: LibraryRepository,
) : LibraryStore {
    constructor(context: Context) : this(LibraryRepository(context))

    override suspend fun loadCached(): CachedLibrary? = repository.loadCached()

    override suspend fun loadLibraryState(): PersistedLibraryState? = repository.loadLibraryState()

    override suspend fun saveLibraryState(state: PersistedLibraryState) = repository.saveLibraryState(state)

    override suspend fun loadSyncCheckpoints(
        sourceIdentity: SourceIdentityKey,
    ): List<LibrarySyncCheckpoint> = repository.loadSyncCheckpoints(sourceIdentity)

    override suspend fun loadRetryItemsPage(
        sourceIdentity: SourceIdentityKey,
        cursor: LibraryRetryCursor,
        limit: Int,
    ): LibraryRetryPage = repository.loadRetryItemsPage(sourceIdentity, cursor, limit)

    override suspend fun loadDueRetryItems(
        sourceIdentity: SourceIdentityKey,
        retryKind: LibraryRetryKind,
        activationEpoch: Long,
        nowMs: Long,
        limit: Int,
    ): List<LibraryRetryItem> = repository.loadDueRetryItems(
        sourceIdentity = sourceIdentity,
        retryKind = retryKind,
        activationEpoch = activationEpoch,
        nowMs = nowMs,
        limit = limit,
    )

    override suspend fun loadRetryItemsForStableObjectKeys(
        sourceIdentity: SourceIdentityKey,
        stableObjectKeys: Collection<String>,
    ): List<LibraryRetryItem> =
        repository.loadRetryItemsForStableObjectKeys(sourceIdentity, stableObjectKeys)

    override suspend fun loadNextRetryAtMsAfter(
        sourceIdentity: SourceIdentityKey,
        activationEpoch: Long,
        afterMs: Long,
    ): Long? = repository.loadNextRetryAtMsAfter(sourceIdentity, activationEpoch, afterMs)

    override suspend fun applyAutoSyncState(mutation: LibraryAutoSyncStateMutation) =
        repository.applyAutoSyncState(mutation)

    override suspend fun loadUserExclusions(
        sourceIdentity: SourceIdentityKey,
    ): List<LibraryUserExclusion> = repository.loadUserExclusions(sourceIdentity)

    override suspend fun upsertUserExclusion(exclusion: LibraryUserExclusion) =
        repository.upsertUserExclusion(exclusion)

    override suspend fun removeUserExclusion(
        sourceIdentity: SourceIdentityKey,
        stableObjectKey: String,
    ) = repository.removeUserExclusion(sourceIdentity, stableObjectKey)

    override suspend fun commitUserExclusionAuthority(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        exclusion: LibraryUserExclusion,
        sortField: SongSortField?,
        sortDirection: SortDirection?,
        fastScrollSectionTargets: Map<String, Int>?,
    ): LibrarySyncResult = repository.commitUserExclusionAuthority(
        songs = songs,
        lastScanAtMs = lastScanAtMs,
        lastScanSource = lastScanSource,
        totalSizeMb = totalSizeMb,
        exclusion = exclusion,
        sortField = sortField,
        sortDirection = sortDirection,
        fastScrollSectionTargets = fastScrollSectionTargets,
    )

    override suspend fun loadFollowupOutboxPage(
        cursor: LibraryFollowupOutboxCursor,
        limit: Int,
    ): LibraryFollowupOutboxPage = repository.loadFollowupOutboxPage(cursor, limit)

    override suspend fun enqueueFollowupOutbox(item: LibraryFollowupOutboxItem) =
        repository.enqueueFollowupOutbox(item)

    override suspend fun acknowledgeFollowupOutbox(eventId: String): Boolean =
        repository.acknowledgeFollowupOutbox(eventId)

    override suspend fun loadLyrics(
        songId: String,
        revision: String,
        priority: List<LyricsSlot>,
    ): LyricsDocument = repository.lyricsById(songId, priority, revision)

    override suspend fun applyLyricsBatch(batch: List<ScannedSongLyrics>) =
        repository.applyLyricsBatch(batch)

    override suspend fun stageLyrics(scanId: String, batch: List<ScannedSongLyrics>) =
        repository.stageLyrics(scanId, batch)

    override suspend fun discardStagedLyrics(scanId: String) =
        repository.discardStagedLyrics(scanId)

    override suspend fun save(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        sortField: SongSortField?,
        sortDirection: SortDirection?,
        fastScrollSectionTargets: Map<String, Int>?,
    ): LibrarySyncResult = repository.save(
        songs,
        lastScanAtMs,
        lastScanSource,
        totalSizeMb,
        sortField,
        sortDirection,
        fastScrollSectionTargets,
    )

    override suspend fun syncIncremental(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        sortField: SongSortField?,
        sortDirection: SortDirection?,
        fastScrollSectionTargets: Map<String, Int>?,
    ): LibrarySyncResult =
        repository.syncIncremental(
            songs,
            lastScanAtMs,
            lastScanSource,
            totalSizeMb,
            sortField,
            sortDirection,
            fastScrollSectionTargets,
        )

    override suspend fun commitScan(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        sortField: SongSortField?,
        sortDirection: SortDirection?,
        fastScrollSectionTargets: Map<String, Int>?,
    ): LibrarySyncResult = repository.commitScan(
        songs,
        lastScanAtMs,
        lastScanSource,
        totalSizeMb,
        sortField,
        sortDirection,
        fastScrollSectionTargets,
    )

    override suspend fun commitAutoSyncSnapshotAuthority(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        state: PersistedLibraryState,
        autoSyncStateMutation: LibraryAutoSyncStateMutation,
        followupOutboxItems: List<LibraryFollowupOutboxItem>,
        stagedLyricsId: String?,
        stagedLyricsMode: LyricsStagingMode,
        stagedExternalLyricsId: String?,
        sortField: SongSortField?,
        sortDirection: SortDirection?,
        fastScrollSectionTargets: Map<String, Int>?,
    ): LibrarySyncResult = repository.commitAutoSyncSnapshotAuthority(
        songs = songs,
        lastScanAtMs = lastScanAtMs,
        lastScanSource = lastScanSource,
        totalSizeMb = totalSizeMb,
        state = state,
        autoSyncStateMutation = autoSyncStateMutation,
        followupOutboxItems = followupOutboxItems,
        stagedLyricsId = stagedLyricsId,
        stagedLyricsMode = stagedLyricsMode,
        stagedExternalLyricsId = stagedExternalLyricsId,
        sortField = sortField,
        sortDirection = sortDirection,
        fastScrollSectionTargets = fastScrollSectionTargets,
    )

    override suspend fun commitAutoSyncDeltaAuthority(
        snapshotSongs: List<Song>,
        delta: LibraryAutoSyncStoreDelta,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        state: PersistedLibraryState,
        autoSyncStateMutation: LibraryAutoSyncStateMutation,
        followupOutboxItems: List<LibraryFollowupOutboxItem>,
        stagedLyricsId: String?,
        stagedLyricsMode: LyricsStagingMode,
        stagedExternalLyricsId: String?,
        sortField: SongSortField?,
        sortDirection: SortDirection?,
        fastScrollSectionTargets: Map<String, Int>?,
    ): LibrarySyncResult = repository.commitAutoSyncDeltaAuthority(
        delta = delta,
        lastScanAtMs = lastScanAtMs,
        lastScanSource = lastScanSource,
        totalSizeMb = totalSizeMb,
        state = state,
        autoSyncStateMutation = autoSyncStateMutation,
        followupOutboxItems = followupOutboxItems,
        stagedLyricsId = stagedLyricsId,
        stagedLyricsMode = stagedLyricsMode,
        stagedExternalLyricsId = stagedExternalLyricsId,
    )

    override suspend fun commitScanAuthorityWithFollowups(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        state: PersistedLibraryState,
        stagedLyricsId: String?,
        followupOutboxItems: List<LibraryFollowupOutboxItem>,
        sortField: SongSortField?,
        sortDirection: SortDirection?,
        fastScrollSectionTargets: Map<String, Int>?,
    ): LibrarySyncResult = repository.commitScanAuthorityWithFollowups(
        songs = songs,
        lastScanAtMs = lastScanAtMs,
        lastScanSource = lastScanSource,
        totalSizeMb = totalSizeMb,
        state = state,
        stagedLyricsId = stagedLyricsId,
        followupOutboxItems = followupOutboxItems,
        sortField = sortField,
        sortDirection = sortDirection,
        fastScrollSectionTargets = fastScrollSectionTargets,
    )

    override suspend fun commitScanAuthority(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        state: PersistedLibraryState,
        autoSyncStateMutation: LibraryAutoSyncStateMutation?,
        stagedLyricsId: String?,
        sortField: SongSortField?,
        sortDirection: SortDirection?,
        fastScrollSectionTargets: Map<String, Int>?,
    ): LibrarySyncResult = repository.commitScanAuthority(
        songs = songs,
        lastScanAtMs = lastScanAtMs,
        lastScanSource = lastScanSource,
        totalSizeMb = totalSizeMb,
        state = state,
        autoSyncStateMutation = autoSyncStateMutation,
        stagedLyricsId = stagedLyricsId,
        sortField = sortField,
        sortDirection = sortDirection,
        fastScrollSectionTargets = fastScrollSectionTargets,
    )

    override suspend fun updatePresentation(
        songIds: List<String>,
        sortField: SongSortField,
        sortDirection: SortDirection,
        fastScrollSectionTargets: Map<String, Int>?,
    ) = repository.updatePresentation(
        songIds,
        sortField,
        sortDirection,
        fastScrollSectionTargets,
    )

    override suspend fun updateCoverColorArgb(songId: String, coverColorArgb: Int) =
        repository.updateCoverColorArgb(songId, coverColorArgb)

    override suspend fun updateBrowseGroups(
        artistGroups: List<BrowseGroup>,
        albumGroups: List<BrowseGroup>,
        artistConfigKey: String,
        artistSortField: ArtistBrowseSortField,
        artistSortDirection: SortDirection,
        artistFastScrollSectionTargets: Map<String, Int>?,
        albumSortField: AlbumBrowseSortField,
        albumSortDirection: SortDirection,
        albumFastScrollSectionTargets: Map<String, Int>?,
    ) = repository.updateBrowseGroups(
        artistGroups,
        albumGroups,
        artistConfigKey,
        artistSortField,
        artistSortDirection,
        artistFastScrollSectionTargets,
        albumSortField,
        albumSortDirection,
        albumFastScrollSectionTargets,
    )

    override suspend fun clearAuthority(state: PersistedLibraryState) = repository.clearAuthority(state)

    override suspend fun clear() = repository.clear()
}

internal class AndroidScanEnvironment(
    private val context: Context,
) : ScanEnvironment {
    override fun hasAudioReadPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_AUDIO
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            },
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun canReadTree(treeUri: Uri): Boolean =
        LibraryFolderStore.canReadTree(context, treeUri)

    override fun hasPersistedTreeReadAccess(treeUri: Uri): Boolean =
        LibraryFolderStore.hasPersistedTreeReadAccess(context, treeUri)

    override fun canAcquireTreeProvider(treeUri: Uri): Boolean =
        LibraryFolderStore.canAcquireTreeProvider(context, treeUri)

    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun playStats(songId: String): PlayStats =
        PlayHistoryStore.getStats(context, songId)

    override fun playStatsSnapshot(songIds: Collection<String>): PlayStatsSnapshot =
        PlayHistoryStore.snapshotStats(context, songIds)

    override fun clearTransientCache() {
        VideoCoverPosterPrefetcher.cancel()
        ScanCacheManager.clearTransientScanCache(context)
    }

    override fun pruneAlbumArtCache(songs: List<Song>) =
        ScanCacheManager.pruneAlbumArtCache(context, songs)

    override fun enqueueVideoCoverPosterPrefetch(
        videoCoverRefs: Collection<com.mica.music.data.scanner.VideoCoverPosterRef>,
    ) {
        if (!PlaybackUiPreferences.videoAlbumCoverEnabled(context)) {
            VideoCoverPosterPrefetcher.cancel()
            return
        }
        VideoCoverPosterPrefetcher.enqueue(context, videoCoverRefs)
    }

    override fun persistLastScanSource(source: ScanSource) =
        LibraryScanSettings.setLastScanSource(context, source)

    override fun lyricsParserVersion(): Int = LibraryScanSettings.lyricsParserVersion(context)

    override fun persistLyricsParserVersion(version: Int) =
        LibraryScanSettings.setLyricsParserVersion(context, version)

    override fun lyricsRetryRequired(): Boolean = LibraryScanSettings.lyricsRetryRequired(context)

    override fun persistLyricsRetryRequired(required: Boolean) =
        LibraryScanSettings.setLyricsRetryRequired(context, required)
}
