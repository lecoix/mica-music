package com.mica.music.data.scanner

import com.mica.music.data.Song

internal enum class SafFingerprintReliability {
    RELIABLE,
    UNKNOWN,
}

internal data class SafTreeMetadataEntry(
    val stableObjectKey: String,
    val mediaUri: String,
    val fileName: String,
    val folderPath: String,
    val filePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val externalLyricsSignature: String,
    val probeDraft: TrackDraft? = null,
) {
    val fingerprintReliability: SafFingerprintReliability
        get() = if (sizeBytes > 0L && lastModifiedMs > 0L) {
            SafFingerprintReliability.RELIABLE
        } else {
            SafFingerprintReliability.UNKNOWN
        }
}

internal fun SafTreeMetadataEntry.hasSameObservedRevision(
    other: SafTreeMetadataEntry,
): Boolean =
    stableObjectKey == other.stableObjectKey &&
        mediaUri == other.mediaUri &&
        fileName == other.fileName &&
        folderPath == other.folderPath &&
        filePath == other.filePath &&
        mimeType == other.mimeType &&
        sizeBytes == other.sizeBytes &&
        lastModifiedMs == other.lastModifiedMs &&
        externalLyricsSignature == other.externalLyricsSignature

internal data class SafTreeMetadataObservationStats(
    val providerQueryCount: Int = 0,
    val directQueryCount: Int = 0,
    val fallbackListingCount: Int = 0,
    val wallTimeMs: Long = 0L,
)

internal data class SafTreeMetadataSnapshot(
    val entries: List<SafTreeMetadataEntry>,
    val discoveryReport: DiscoveryReport,
    val videoCovers: List<VideoCoverFile> = emptyList(),
    val observationStats: SafTreeMetadataObservationStats = SafTreeMetadataObservationStats(),
)

/**
 * Post-probe SAF observation scoped to the exact folders touched by this AUTO pass.
 *
 * Unlike [SafTreeMetadataSnapshot], this is deliberately not a whole-tree authority snapshot.
 * Each requested folder is resolved again from the granted tree root, so rename/move/missing
 * races fail closed without paying for an unrelated recursive tree walk.
 */
internal data class SafTargetedMetadataSnapshot(
    val entries: List<SafTreeMetadataEntry>,
    val videoCovers: List<VideoCoverFile> = emptyList(),
    val requestedFolderPaths: Set<String>,
    val completeFolderPaths: Set<String>,
    val failedFolderPaths: Set<String> = emptySet(),
    val observationStats: SafTreeMetadataObservationStats = SafTreeMetadataObservationStats(),
) {
    val isComplete: Boolean
        get() = failedFolderPaths.isEmpty() && completeFolderPaths.containsAll(requestedFolderPaths)

    fun isFolderComplete(folderPath: String): Boolean = folderPath in completeFolderPaths
}

internal data class SafFastVerifyPlan(
    val added: List<SafTreeMetadataEntry>,
    val changed: List<SafTreeMetadataEntry>,
    val unknownFingerprint: List<SafTreeMetadataEntry>,
    val removedStableObjectKeys: Set<String>,
    val unchangedCount: Int,
    val removalSuppressedCount: Int,
    val discoveryReport: DiscoveryReport,
) {
    val isNoOp: Boolean
        get() = added.isEmpty() &&
            changed.isEmpty() &&
            unknownFingerprint.isEmpty() &&
            removedStableObjectKeys.isEmpty()
}

internal object SafFastVerifyPlanner {

    fun plan(
        snapshot: SafTreeMetadataSnapshot,
        cachedSongs: List<Song>,
        excludedStableObjectKeys: Set<String> = emptySet(),
        cachedSongById: ((String) -> Song?)? = null,
    ): SafFastVerifyPlan {
        // Production already owns a catalog id index in MusicLibraryBacking. Reuse it when supplied
        // instead of retaining a second O(N) Song map for the duration of every SAF metadata walk.
        val fallbackCachedById = if (cachedSongById == null) {
            cachedSongs.associateBy(Song::id)
        } else {
            emptyMap()
        }
        fun cached(id: String): Song? = cachedSongById?.invoke(id) ?: fallbackCachedById[id]

        val seen = LinkedHashSet<String>(snapshot.entries.size)
        val added = mutableListOf<SafTreeMetadataEntry>()
        val changed = mutableListOf<SafTreeMetadataEntry>()
        val unknownFingerprint = mutableListOf<SafTreeMetadataEntry>()
        var unchangedCount = 0

        snapshot.entries.forEach { entry ->
            seen += entry.stableObjectKey
            if (entry.stableObjectKey in excludedStableObjectKeys) return@forEach
            val current = cached(entry.stableObjectKey)
            if (current == null) {
                added += entry
                return@forEach
            }
            val observableNonFingerprintChange =
                entry.hasObservableNonFingerprintDifference(current)
            if (
                entry.fingerprintReliability == SafFingerprintReliability.UNKNOWN ||
                current.sizeBytes <= 0L ||
                current.dateModifiedMs <= 0L
            ) {
                if (observableNonFingerprintChange) {
                    changed += entry
                } else {
                    unknownFingerprint += entry
                }
                return@forEach
            }
            if (observableNonFingerprintChange || entry.hasFingerprintDifference(current)) {
                changed += entry
            } else {
                unchangedCount += 1
            }
        }

        val missing = cachedSongs.asSequence()
            .map(Song::id)
            .filterNot(seen::contains)
            .toSet()
        val discoveryComplete = snapshot.discoveryReport.isComplete(DiscoveryPartitions.SAF_TREE)
        val removed = if (discoveryComplete) missing else emptySet()
        val suppressed = if (discoveryComplete) 0 else missing.size

        return SafFastVerifyPlan(
            added = added,
            changed = changed,
            unknownFingerprint = unknownFingerprint,
            removedStableObjectKeys = removed,
            unchangedCount = unchangedCount,
            removalSuppressedCount = suppressed,
            discoveryReport = snapshot.discoveryReport,
        )
    }

    private fun SafTreeMetadataEntry.hasObservableNonFingerprintDifference(
        cached: Song,
    ): Boolean =
        mediaUri != cached.mediaUri ||
            fileName != cached.fileName ||
            folderPath != cached.folderPath ||
            filePath != cached.filePath ||
            externalLyricsSignature != cached.externalLyricsSignature

    private fun SafTreeMetadataEntry.hasFingerprintDifference(cached: Song): Boolean =
        sizeBytes != cached.sizeBytes ||
            lastModifiedMs != cached.dateModifiedMs
}
