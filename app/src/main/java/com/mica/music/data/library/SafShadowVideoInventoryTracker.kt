package com.mica.music.data.library

import com.mica.music.data.scanner.VideoCoverFile

/**
 * Shadow-only S4 anchor for SAF MP4 sidecar inventory.
 *
 * A Folder Full Scan seeds the complete baseline. FAST metadata walks then expose only folders whose
 * MP4 membership/revision actually changed, so steady-state no-op verification does not require a
 * second full SAF walk merely because the library contains video files.
 */
internal class SafShadowVideoInventoryTracker {
    private var baselineByFolder: Map<String, List<VideoCoverFile>>? = null

    @Synchronized
    fun seed(files: List<VideoCoverFile>) {
        baselineByFolder = normalize(files)
    }

    @Synchronized
    fun changedFolders(
        files: List<VideoCoverFile>,
        conservativeFoldersWhenUnseeded: Set<String> = emptySet(),
    ): Set<String> {
        val observed = normalize(files)
        val baseline = baselineByFolder
            ?: return (observed.keys + conservativeFoldersWhenUnseeded).toSortedSet()
        return (baseline.keys + observed.keys)
            .filterTo(sortedSetOf()) { folder -> baseline[folder].orEmpty() != observed[folder].orEmpty() }
    }

    @Synchronized
    fun seedIfAbsent(files: List<VideoCoverFile>) {
        if (baselineByFolder == null) {
            baselineByFolder = normalize(files)
        }
    }

    @Synchronized
    fun acceptFolders(
        files: List<VideoCoverFile>,
        folderPaths: Set<String>,
    ) {
        if (folderPaths.isEmpty()) return
        val observed = normalize(files)
        val next = (baselineByFolder ?: emptyMap()).toMutableMap()
        folderPaths.forEach { folder ->
            val folderFiles = observed[folder].orEmpty()
            if (folderFiles.isEmpty()) {
                next.remove(folder)
            } else {
                next[folder] = folderFiles
            }
        }
        baselineByFolder = next.toSortedMap()
    }

    @Synchronized
    fun reset() {
        baselineByFolder = null
    }

    private fun normalize(
        files: List<VideoCoverFile>,
    ): Map<String, List<VideoCoverFile>> = files
        .groupBy(VideoCoverFile::folderPath)
        .mapValues { (_, folderFiles) ->
            folderFiles.sortedWith(
                compareBy<VideoCoverFile>(
                    VideoCoverFile::uri,
                    VideoCoverFile::baseName,
                    VideoCoverFile::sizeBytes,
                    VideoCoverFile::lastModifiedMs,
                ),
            )
        }
        .toSortedMap()
}
