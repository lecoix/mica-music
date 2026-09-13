package com.mica.music.data.library

import com.mica.music.data.ScanSource

/** Executes targeted metadata refreshes without owning scheduling or publication. */
internal class TargetedMetadataRefreshExecutor(
    private val backing: MusicLibraryBacking,
    private val fullScanExecutor: FullLibraryScanExecutor,
) {
    suspend fun refresh(
        songIds: Set<String>,
        operation: ScheduledLibraryOperation? = null,
    ) {
        val targets = songIds.filterTo(linkedSetOf()) { id ->
            id.isNotBlank() && backing.songById(id) != null
        }
        if (targets.isEmpty()) return

        when (backing.lastScanSource) {
            ScanSource.FOLDER -> if (backing.folder.hasLibraryFolder()) {
                fullScanExecutor.scanLibraryFolder(
                    forceRefreshSongIds = targets,
                    operation = operation,
                    artworkOnly = operation?.request?.cause == LibraryOperationCause.AUTO_ARTWORK_HYDRATE,
                )
            }
            ScanSource.DEVICE -> if (backing.folder.hasAudioReadPermission()) {
                fullScanExecutor.scanDeviceWide(
                    forceRefreshSongIds = targets,
                    operation = operation,
                )
            }
        }
    }
}