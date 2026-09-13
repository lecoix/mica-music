package com.mica.music.data

import com.mica.music.data.local.CachedLibrary
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
import com.mica.music.data.library.LibraryUserExclusion
import com.mica.music.data.library.LyricsStagingMode
import com.mica.music.data.library.PersistedLibraryState
import com.mica.music.data.library.SourceIdentityKey

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
