package com.mica.music.data

import android.content.Context
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
import com.mica.music.data.library.LibrarySyncCheckpoint
import com.mica.music.data.library.LibraryUserExclusion
import com.mica.music.data.library.LyricsStagingMode
import com.mica.music.data.library.PersistedLibraryState
import com.mica.music.data.library.SourceIdentityKey

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
