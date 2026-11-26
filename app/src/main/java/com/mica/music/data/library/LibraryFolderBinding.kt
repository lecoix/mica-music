package com.mica.music.data.library

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import com.mica.music.data.LibraryFolderEntry
import com.mica.music.data.LibraryFolderSet
import com.mica.music.data.LibraryFolderStore
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.launch

internal class LibraryFolderBinding(
    private val backing: MusicLibraryBacking,
) {
    fun reloadLibraryFolderFromPrefs() {
        applyActiveFolders(LibraryScanSettings.libraryFolders(backing.context))
        applyPendingFolders(LibraryScanSettings.pendingLibraryFolders(backing.context))
    }

    fun hasLibraryFolder(): Boolean = scanFolders().isNotEmpty()

    fun activeFolders(): List<LibraryFolderEntry> = backing.libraryFolders

    fun scanFolders(): List<LibraryFolderEntry> =
        backing.pendingLibraryFolders ?: backing.libraryFolders

    fun activeTreeUris(): List<Uri> =
        backing.libraryFolders.map { it.treeUri.toUri() }

    fun scanTreeUris(): List<Uri> =
        scanFolders().map { it.treeUri.toUri() }

    fun scanTreeUri(): Uri? = scanTreeUris().firstOrNull()

    fun displayFolderLabel(): String? =
        backing.pendingLibraryFolderLabel ?: backing.libraryFolderLabel

    fun setLibraryFolder(treeUri: Uri) {
        LibraryFolderStore.persistTreeAccess(backing.context, treeUri)
        val label = LibraryFolderStore.displayName(backing.context, treeUri)
        setPendingLibraryFolders(listOf(LibraryFolderEntry(treeUri.toString(), label)))
    }

    fun setPendingLibraryFolders(folders: List<LibraryFolderEntry>) {
        val normalized = LibraryFolderSet.normalize(folders)
        if (normalized.isEmpty()) {
            clearLibraryFolder()
            return
        }
        normalized.forEach { entry ->
            val uri = entry.treeUri.toUri()
            if (!LibraryFolderStore.hasPersistedTreeReadAccess(backing.context, uri)) {
                runCatching { LibraryFolderStore.persistTreeAccess(backing.context, uri) }
            }
        }
        LibraryScanSettings.setPendingLibraryFolders(backing.context, normalized)
        applyPendingFolders(normalized)
        if (backing.accessState == LibraryAccessState.TEMP_UNAVAILABLE) {
            backing.operationExecutor.resetSafProviderDiscoveryBackoff()
            backing.launchAccessStateUpdate(LibraryAccessState.AVAILABLE)
        }
    }

    fun clearLibraryFolder() {
        val allUris = linkedSetOf<String>()
        backing.pendingLibraryFolders.orEmpty().mapTo(allUris) { it.treeUri }
        backing.libraryFolders.mapTo(allUris) { it.treeUri }
        backing.pendingLibraryFolderUri?.let(allUris::add)
        backing.libraryFolderUri?.let(allUris::add)
        allUris.map(String::toUri).forEach { uri ->
            LibraryFolderStore.releaseTreeAccess(backing.context, uri)
        }
        LibraryScanSettings.clearPendingLibraryFolder(backing.context)
        LibraryScanSettings.clearLibraryFolder(backing.context)
        applyPendingFolders(null)
        applyActiveFolders(emptyList())
    }

    fun commitPendingFolderIfActivated(token: LibraryOperationToken) {
        val targetUris = token.sourceIdentity.folderTreeUris()
        if (targetUris.isEmpty()) return
        val active = backing.sourceState.active ?: return
        if (
            active.sourceIdentity != token.sourceIdentity ||
            active.activationEpoch != token.activationEpoch
        ) {
            return
        }
        val pending = backing.pendingLibraryFolders ?: return
        val pendingIdentity = SourceIdentityKey.folders(pending.map { it.treeUri }) ?: return
        if (pendingIdentity != token.sourceIdentity) return

        val previousFolders = backing.libraryFolders
        val previousUris = previousFolders.mapTo(linkedSetOf()) { it.treeUri }
        val committedUris = pending.mapTo(linkedSetOf()) { it.treeUri }
        migrateExcludedDirectoriesForFolderTransition(previousFolders, pending)
        LibraryScanSettings.setLibraryFolders(backing.context, pending)
        LibraryScanSettings.clearPendingLibraryFolder(backing.context)
        applyActiveFolders(pending)
        applyPendingFolders(null)
        (previousUris - committedUris).forEach { removed ->
            LibraryFolderStore.releaseTreeAccess(backing.context, removed.toUri())
        }
    }

    fun discardPendingFolderIfMatches(token: LibraryOperationToken) {
        val pending = backing.pendingLibraryFolders ?: return
        val pendingIdentity = SourceIdentityKey.folders(pending.map { it.treeUri }) ?: return
        if (pendingIdentity != token.sourceIdentity) return
        val activeUris = backing.libraryFolders.mapTo(linkedSetOf()) { it.treeUri }
        pending.map { it.treeUri }
            .filterNot { it in activeUris }
            .forEach { unused ->
                LibraryFolderStore.releaseTreeAccess(backing.context, unused.toUri())
            }
        LibraryScanSettings.clearPendingLibraryFolder(backing.context)
        applyPendingFolders(null)
    }

    fun discardPendingFolderSelection() {
        if (backing.pendingLibraryFolders == null && backing.pendingLibraryFolderUri == null) return
        val activeUris = backing.libraryFolders.mapTo(linkedSetOf()) { it.treeUri }
        backing.pendingLibraryFolders.orEmpty()
            .map { it.treeUri }
            .filterNot { it in activeUris }
            .forEach { unused ->
                LibraryFolderStore.releaseTreeAccess(backing.context, unused.toUri())
            }
        LibraryScanSettings.clearPendingLibraryFolder(backing.context)
        applyPendingFolders(null)
    }

    fun reconcileWithPersistedSourceState() {
        val activeIdentity = backing.sourceState.active?.sourceIdentity ?: return
        val activeUris = activeIdentity.folderTreeUris()
        if (activeUris.isEmpty()) return

        val pending = backing.pendingLibraryFolders
        val pendingMatches = pending != null &&
            SourceIdentityKey.folders(pending.map { it.treeUri }) == activeIdentity
        val labelByUri = buildMap {
            backing.libraryFolders.forEach { put(it.treeUri, it.label) }
            pending.orEmpty().forEach { put(it.treeUri, it.label) }
        }
        val orderedEntries = when {
            pendingMatches -> pending!!
            SourceIdentityKey.folders(backing.libraryFolders.map { it.treeUri }) == activeIdentity ->
                backing.libraryFolders
            else -> activeUris.map { uriString ->
                val label = labelByUri[uriString]
                    ?: LibraryFolderStore.displayName(backing.context, uriString.toUri())
                LibraryFolderEntry(uriString, label)
            }
        }
        val previousFolders = backing.libraryFolders
        val normalized = LibraryFolderSet.normalize(orderedEntries)
        migrateExcludedDirectoriesForFolderTransition(previousFolders, normalized)
        applyActiveFolders(normalized)
        LibraryScanSettings.setLibraryFolders(backing.context, normalized)
        if (pendingMatches) {
            LibraryScanSettings.clearPendingLibraryFolder(backing.context)
            applyPendingFolders(null)
        }
    }

    private fun migrateExcludedDirectoriesForFolderTransition(
        previousFolders: List<LibraryFolderEntry>,
        nextFolders: List<LibraryFolderEntry>,
    ) {
        if (previousFolders.isEmpty() || nextFolders.isEmpty()) return
        val prevUris = previousFolders.map { it.treeUri.toUri() }
        val nextUris = nextFolders.map { it.treeUri.toUri() }
        if (prevUris == nextUris) return
        val currentExcluded = LibraryScanSettings.excludedScanDirectories(backing.context)
        if (currentExcluded.isEmpty()) return
        val migrated = LibraryFolderSet.migrateExcludedDirectories(
            context = backing.context,
            excludedDirectories = currentExcluded,
            previousTreeUris = prevUris,
            nextTreeUris = nextUris,
        )
        if (migrated != currentExcluded) {
            LibraryScanSettings.setExcludedScanDirectories(backing.context, migrated)
            backing.configFingerprint = LibraryScanSettings.configFingerprint(backing.context)
        }
    }

    private fun applyActiveFolders(folders: List<LibraryFolderEntry>) {
        val normalized = LibraryFolderSet.normalize(folders)
        backing.libraryFolders = normalized
        if (normalized.isEmpty()) {
            backing.libraryFolderLabel = null
        }
    }

    private fun applyPendingFolders(folders: List<LibraryFolderEntry>?) {
        val normalized = folders?.let(LibraryFolderSet::normalize)?.takeIf(List<LibraryFolderEntry>::isNotEmpty)
        backing.pendingLibraryFolders = normalized
        if (normalized == null) {
            backing.pendingLibraryFolderLabel = null
        }
    }

    fun updatePermission(granted: Boolean) {
        DiagnosticLog.event(
            "LibraryResume",
            "updatePermission granted=$granted previous=${backing.permissionGranted} " +
                "hasFolder=${hasLibraryFolder()} hasScanned=${backing.hasScanned} songs=${backing.songs.size}",
        )
        backing.permissionGranted = granted
        val activeSource = backing.sourceState.active?.sourceIdentity?.source
        val recoveringSafProvider =
            activeSource == com.mica.music.data.ScanSource.FOLDER &&
                backing.accessState == LibraryAccessState.TEMP_UNAVAILABLE
        if (recoveringSafProvider) {
            backing.operationExecutor.resetSafProviderDiscoveryBackoff()
        }
        val access = when {
            activeSource == com.mica.music.data.ScanSource.FOLDER -> LibraryAccessState.AVAILABLE
            granted -> LibraryAccessState.AVAILABLE
            else -> LibraryAccessState.PERMISSION_REQUIRED
        }
        backing.launchAccessStateUpdate(access)
    }

    fun audioReadPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasAudioReadPermission(): Boolean =
        backing.scanEnvironment.hasAudioReadPermission()

    fun clearLibrary() {
        backing.syncScheduler.cancelAll()
        backing.scanJob?.cancel()
        backing.scanJob = null
        backing.isScanning = false
        backing.isUserVisibleScanning = false
        backing.isLoadingCachedLibrary = false
        backing.scanProgressLabel = null
        backing.scanJob = backing.scanScope.launch {
            publishEmptyLibrarySnapshot()
        }
    }

    private suspend fun publishEmptyLibrarySnapshot() {
        val clearedState = backing.clearedPersistedState()
        backing.replaceSnapshotAuthority(
            storeBlock = {
                backing.libraryStore.clearAuthority(clearedState)
            },
            publishBlock = { _, _ ->
                backing.restorePersistedState(clearedState)
                backing.catalog.clearCatalog()
                backing.hasScanned = false
                backing.totalSizeMb = 0
                backing.lastScanAtMs = null
                backing.lastScanError = null
                backing.lastScanSyncSummary = null
            },
        ) ?: return
    }
}
