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
    ): SafFastVerifyPlan {
        val cachedById = cachedSongs.associateBy(Song::id)
        val seen = LinkedHashSet<String>(snapshot.entries.size)
        val added = mutableListOf<SafTreeMetadataEntry>()
        val changed = mutableListOf<SafTreeMetadataEntry>()
        val unknownFingerprint = mutableListOf<SafTreeMetadataEntry>()
        var unchangedCount = 0

        snapshot.entries.forEach { entry ->
            seen += entry.stableObjectKey
            if (entry.stableObjectKey in excludedStableObjectKeys) return@forEach
            val cached = cachedById[entry.stableObjectKey]
            if (cached == null) {
                added += entry
                return@forEach
            }
            val observableNonFingerprintChange =
                entry.hasObservableNonFingerprintDifference(cached)
            if (
                entry.fingerprintReliability == SafFingerprintReliability.UNKNOWN ||
                cached.sizeBytes <= 0L ||
                cached.dateModifiedMs <= 0L
            ) {
                if (observableNonFingerprintChange) {
                    changed += entry
                } else {
                    unknownFingerprint += entry
                }
                return@forEach
            }
            if (observableNonFingerprintChange || entry.hasFingerprintDifference(cached)) {
                changed += entry
            } else {
                unchangedCount += 1
            }
        }

        val missing = cachedById.keys - seen
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
