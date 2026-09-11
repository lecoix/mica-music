package com.mica.music.data.library

import com.mica.music.data.LibraryAutoSyncStoreDelta
import com.mica.music.data.LibraryAutoSyncStoreRow
import com.mica.music.data.ScanSource
import com.mica.music.data.ScannedSongLyrics
import com.mica.music.data.SharedLyricsMemoryCache
import com.mica.music.data.Song
import com.mica.music.data.local.LibrarySyncResult
import com.mica.music.data.scanner.AutoSyncPublicationDecision
import com.mica.music.data.scanner.AutoSyncVisibleDelta
import com.mica.music.data.scanner.publicationDecision
import com.mica.music.util.DiagnosticLog

/**
 * Owns the final AUTO publication bridge.
 *
 * Source-specific pipelines build plans; this authority validates/stages/rebases and delegates the
 * atomic Room -> memory publication gate to [MusicLibraryBacking]. It deliberately does not own
 * discovery, retry scheduling or dirty-signal policy.
 */
internal class AutoSyncPublicationAuthority(
    private val backing: MusicLibraryBacking,
) {
    private val catalog get() = backing.catalog

    suspend fun publishDevicePlan(
        token: LibraryOperationToken,
        scanStartSnapshot: List<Song>,
        plan: DeviceAutoSyncPublicationPlan,
    ): LibrarySyncResult? {
        require(token.mode == LibraryOperationMode.AUTO_SYNC)
        require(token.sourceIdentity.source == ScanSource.DEVICE)
        require(plan.autoSyncStateMutation.sourceIdentity == token.sourceIdentity)

        if (!plan.hasAuthorityMutation) {
            return backing.withCurrentOperationIfCurrent(token) {
                LibrarySyncResult(
                    added = 0,
                    updated = 0,
                    removed = 0,
                    unchanged = backing.songs.size,
                )
            }
        }

        val baseStagingId = backing.operationStagingId(token)
        val fullStagingId = baseStagingId.takeIf { plan.fullLyricsToStage.isNotEmpty() }
        val externalStagingId = "$baseStagingId-external"
            .takeIf { plan.externalLyricsToStage.isNotEmpty() }
        try {
            if (fullStagingId != null) {
                val staged = backing.storeWriteIfCurrentOperation(token) {
                    backing.libraryStore.stageLyrics(fullStagingId, plan.fullLyricsToStage)
                }
                if (!staged) return null
            }
            if (externalStagingId != null) {
                val staged = backing.storeWriteIfCurrentOperation(token) {
                    backing.libraryStore.stageLyrics(externalStagingId, plan.externalLyricsToStage)
                }
                if (!staged) return null
            }

            val result = publishSnapshot(
                token = token,
                scanStartSnapshot = scanStartSnapshot,
                nextSnapshot = plan.nextSnapshot,
                visibleDelta = plan.visibleDelta,
                membershipChanges = plan.membershipChanges,
                autoSyncStateMutation = plan.autoSyncStateMutation,
                stagedLyricsId = fullStagingId,
                stagedExternalLyricsId = externalStagingId,
            )
            if (
                result != null &&
                (plan.fullLyricsToStage.isNotEmpty() || plan.externalLyricsToStage.isNotEmpty())
            ) {
                SharedLyricsMemoryCache.invalidateSongs(
                    (plan.fullLyricsToStage + plan.externalLyricsToStage)
                        .map(ScannedSongLyrics::songId),
                )
            }
            return result
        } finally {
            fullStagingId?.let { backing.discardOperationStaging(it) }
            externalStagingId?.let { backing.discardOperationStaging(it) }
        }
    }

    suspend fun publishSafPlan(
        token: LibraryOperationToken,
        scanStartSnapshot: List<Song>,
        plan: SafAutoSyncPublicationPlan,
    ): LibrarySyncResult? {
        require(token.mode == LibraryOperationMode.AUTO_SYNC)
        require(token.sourceIdentity.source == ScanSource.FOLDER)
        require(plan.autoSyncStateMutation.sourceIdentity == token.sourceIdentity)

        if (!plan.hasAuthorityMutation) {
            return backing.withCurrentOperationIfCurrent(token) {
                LibrarySyncResult(
                    added = 0,
                    updated = 0,
                    removed = 0,
                    unchanged = backing.songs.size,
                )
            }
        }

        val stagingId = backing.operationStagingId(token)
            .takeIf { plan.lyricsToStage.isNotEmpty() }
        try {
            if (stagingId != null) {
                val staged = backing.storeWriteIfCurrentOperation(token) {
                    backing.libraryStore.stageLyrics(stagingId, plan.lyricsToStage)
                }
                if (!staged) return null
            }

            val result = publishSnapshot(
                token = token,
                scanStartSnapshot = scanStartSnapshot,
                nextSnapshot = plan.nextSnapshot,
                visibleDelta = plan.visibleDelta,
                membershipChanges = plan.membershipChanges,
                autoSyncStateMutation = plan.autoSyncStateMutation,
                stagedLyricsId = stagingId,
            )
            if (result != null && plan.lyricsToStage.isNotEmpty()) {
                SharedLyricsMemoryCache.invalidateSongs(
                    plan.lyricsToStage.map(ScannedSongLyrics::songId),
                )
            }
            return result
        } finally {
            stagingId?.let { backing.discardOperationStaging(it) }
        }
    }

    suspend fun publishSnapshot(
        token: LibraryOperationToken,
        scanStartSnapshot: List<Song>,
        nextSnapshot: List<Song>,
        visibleDelta: AutoSyncVisibleDelta,
        membershipChanges: List<MembershipChange>,
        autoSyncStateMutation: LibraryAutoSyncStateMutation,
        stagedLyricsId: String? = null,
        stagedExternalLyricsId: String? = null,
    ): LibrarySyncResult? {
        require(token.mode == LibraryOperationMode.AUTO_SYNC)
        require(autoSyncStateMutation.sourceIdentity == token.sourceIdentity)
        require(backing.intentState == LibraryIntentState.ACTIVE) {
            "AUTO sync requires an established active library baseline"
        }
        require(membershipChanges.all { it.sourceIdentity == token.sourceIdentity }) {
            "AUTO membership changes must belong to the active operation source"
        }
        require(
            visibleDelta.removedStableObjectKeys ==
                membershipChanges.mapTo(linkedSetOf(), MembershipChange::stableObjectKey),
        ) {
            "AUTO removal delta and membership evidence must describe the same objects"
        }

        if (visibleDelta.publicationDecision() == AutoSyncPublicationDecision.CHECKPOINT_ONLY) {
            check(membershipChanges.isEmpty())
            return if (
                backing.commitAutoSyncCheckpointOnlyIfCurrent(
                    token = token,
                    visibleDelta = visibleDelta,
                    mutation = autoSyncStateMutation,
                )
            ) {
                LibrarySyncResult(0, 0, 0, backing.songs.size)
            } else {
                null
            }
        }

        repeat(MAX_PUBLICATION_REBASE_ATTEMPTS) { attempt ->
            if (!backing.isCurrentOperationToken(token)) return null
            val field = backing.sortField
            val direction = backing.sortDirection
            val publicationRaw = rebaseScanResultForCurrentCatalog(
                scanned = nextSnapshot,
                scanStartCatalog = scanStartSnapshot,
                currentCatalog = catalog.scannedSongsSnapshot().takeIf { it.isNotEmpty() } ?: backing.songs,
                catalogChanged = backing.catalogRevision != token.catalogRevisionAtStart,
            )
            val prepared = catalog.prepareLibrarySongs(
                raw = publicationRaw,
                field = field,
                direction = direction,
                diagnosticTag = "LibraryAutoSync",
                diagnosticReason = "autoPublish",
                releaseLoadedLyrics = true,
            )
            val storeDelta = prepareAutoSyncStoreDelta(
                snapshotSongs = prepared.visible,
                visibleDelta = visibleDelta,
                membershipChanges = membershipChanges,
            )
            val totalSizeMb =
                (prepared.visible.sumOf { it.sizeBytes.coerceAtLeast(0L) } / (1024L * 1024L)).toInt()
            val lastFullScanAtMs = requireNotNull(backing.lastScanAtMs) {
                "ACTIVE library must retain its last full-scan timestamp"
            }
            val lastFullScanSource = backing.lastScanSource
            val result = backing.commitAutoSyncSnapshotAndPublishIfCurrent(
                token = token,
                expectedCatalogRevision = prepared.catalogRevision,
                expectedPresentationRevision = prepared.presentationRevision,
                changeSetForRevision = { revision ->
                    LibraryChangeSet(
                        libraryRevision = revision,
                        cause = token.cause,
                        addedIds = visibleDelta.addedIds,
                        updatedIds = visibleDelta.updatedIds,
                        membershipChanges = membershipChanges,
                    )
                },
                storeBlock = { changeSet ->
                    val followups = LibraryFollowupProtocol.planPlaylistRemovalFollowups(
                        changeSet = changeSet,
                        activationEpoch = token.activationEpoch,
                        createdAtMs = backing.scanEnvironment.currentTimeMillis(),
                    )
                    backing.libraryStore.commitAutoSyncDeltaAuthority(
                        snapshotSongs = prepared.visible,
                        delta = storeDelta,
                        lastScanAtMs = lastFullScanAtMs,
                        lastScanSource = lastFullScanSource,
                        totalSizeMb = totalSizeMb,
                        state = backing.persistedStateAfterActivation(token),
                        autoSyncStateMutation = autoSyncStateMutation,
                        followupOutboxItems = followups,
                        stagedLyricsId = stagedLyricsId,
                        stagedExternalLyricsId = stagedExternalLyricsId,
                        sortField = field,
                        sortDirection = direction,
                        fastScrollSectionTargets = prepared.fastScrollIndex?.sectionTargets,
                    )
                },
                publishBlock = { _, _ ->
                    backing.activateOperationSourceAfterFinalCommit(token)
                    catalog.adoptPrepared(prepared)
                    backing.totalSizeMb = totalSizeMb
                    backing.hasScanned = true
                    catalog.persistPreparedCustomOrderIfCurrent(prepared)
                },
            )
            if (result != null) return result
            if (!backing.isCurrentOperationToken(token)) return null
            DiagnosticLog.event(
                "LibraryAutoSync",
                "autoPublish rebase-retry attempt=$attempt catalogRevision=${backing.catalogRevision}",
            )
        }
        return null
    }

    private fun prepareAutoSyncStoreDelta(
        snapshotSongs: List<Song>,
        visibleDelta: AutoSyncVisibleDelta,
        membershipChanges: List<MembershipChange>,
    ): LibraryAutoSyncStoreDelta {
        require(visibleDelta.addedIds.intersect(visibleDelta.updatedIds).isEmpty()) {
            "AUTO added/updated ids must be disjoint"
        }
        val upsertIds = linkedSetOf<String>().apply {
            addAll(visibleDelta.addedIds)
            addAll(visibleDelta.updatedIds)
        }
        val upsertRows = ArrayList<LibraryAutoSyncStoreRow>(upsertIds.size)
        snapshotSongs.forEachIndexed { index, song ->
            if (song.id in upsertIds) {
                upsertRows += LibraryAutoSyncStoreRow(song = song, queueOrderHint = index)
            }
        }
        require(upsertRows.mapTo(linkedSetOf()) { it.song.id } == upsertIds) {
            "AUTO visible delta contains ids absent from the prepared snapshot"
        }

        val removedSongIds = membershipChanges.map { change ->
            requireNotNull(change.songId?.takeIf(String::isNotBlank)) {
                "Destructive AUTO membership change must retain the published song id"
            }
        }.distinct()
        require(removedSongIds.size == membershipChanges.size) {
            "AUTO membership changes must map one-to-one to distinct song ids"
        }

        return LibraryAutoSyncStoreDelta(
            upsertRows = upsertRows,
            removedSongIds = removedSongIds,
            snapshotSongCount = snapshotSongs.size,
            addedCount = visibleDelta.addedIds.size,
            updatedCount = visibleDelta.updatedIds.size,
        )
    }
}

internal fun rebaseScanResultForCurrentCatalog(
    scanned: List<Song>,
    scanStartCatalog: List<Song>,
    currentCatalog: List<Song>,
    catalogChanged: Boolean,
): List<Song> {
    if (!catalogChanged) return scanned

    val startIds = scanStartCatalog.mapTo(HashSet(scanStartCatalog.size), Song::id)
    val currentById = currentCatalog.associateBy(Song::id)
    val currentIds = currentById.keys
    val locallyRemovedIds = startIds - currentIds
    val scannerIds = scanned.mapTo(HashSet(scanned.size), Song::id)

    val rebased = ArrayList<Song>(scanned.size + currentCatalog.size)
    scanned.forEach { scannedSong ->
        if (scannedSong.id in locallyRemovedIds) return@forEach
        val current = currentById[scannedSong.id]
        rebased += if (current == null) {
            scannedSong
        } else {
            scannedSong.copy(
                coverColorArgb = current.coverColorArgb,
                playbackUri = current.playbackUri,
                playCount = current.playCount,
                totalListenSeconds = current.totalListenSeconds,
                lastPlayedAtMs = current.lastPlayedAtMs,
                loudnessAnalysis = current.loudnessAnalysis,
            )
        }
    }

    currentCatalog.forEach { current ->
        if (current.id !in startIds && current.id !in scannerIds) {
            rebased += current
        }
    }
    return rebased
}

internal const val MAX_PUBLICATION_REBASE_ATTEMPTS = 2
