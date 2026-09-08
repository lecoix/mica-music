package com.mica.music.data.library

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.net.toUri
import com.mica.music.data.LibraryFolderStore
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.launch

internal class LibraryFolderBinding(
    private val backing: MusicLibraryBacking,
) {
    fun reloadLibraryFolderFromPrefs() {
        val activeUri = LibraryScanSettings.libraryTreeUri(backing.context)
        val pendingUri = LibraryScanSettings.pendingLibraryTreeUri(backing.context)
        backing.libraryFolderUri = activeUri?.toString()
        backing.libraryFolderLabel = LibraryScanSettings.libraryFolderLabel(backing.context)
        backing.pendingLibraryFolderUri = pendingUri?.toString()
        backing.pendingLibraryFolderLabel = LibraryScanSettings.pendingLibraryFolderLabel(backing.context)
    }

    fun hasLibraryFolder(): Boolean =
        !backing.pendingLibraryFolderUri.isNullOrBlank() || !backing.libraryFolderUri.isNullOrBlank()

    fun scanTreeUri(): Uri? =
        (backing.pendingLibraryFolderUri ?: backing.libraryFolderUri)?.toUri()

    fun displayFolderLabel(): String? =
        backing.pendingLibraryFolderLabel ?: backing.libraryFolderLabel

    fun setLibraryFolder(treeUri: Uri) {
        LibraryFolderStore.persistTreeAccess(backing.context, treeUri)
        val label = LibraryFolderStore.displayName(backing.context, treeUri)
        LibraryScanSettings.setPendingLibraryFolder(backing.context, treeUri, label)
        backing.pendingLibraryFolderUri = treeUri.toString()
        backing.pendingLibraryFolderLabel = label
    }

    fun clearLibraryFolder() {
        linkedSetOf(backing.pendingLibraryFolderUri, backing.libraryFolderUri)
            .filterNotNull()
            .map(String::toUri)
            .forEach { uri ->
                LibraryFolderStore.releaseTreeAccess(backing.context, uri)
            }
        LibraryScanSettings.clearPendingLibraryFolder(backing.context)
        LibraryScanSettings.clearLibraryFolder(backing.context)
        backing.pendingLibraryFolderUri = null
        backing.pendingLibraryFolderLabel = null
        backing.libraryFolderUri = null
        backing.libraryFolderLabel = null
    }

    fun commitPendingFolderIfActivated(token: LibraryOperationToken) {
        val target = token.sourceIdentity.folderTreeUriOrNull() ?: return
        val active = backing.sourceState.active ?: return
        if (
            active.sourceIdentity != token.sourceIdentity ||
            active.activationEpoch != token.activationEpoch
        ) {
            return
        }
        if (backing.pendingLibraryFolderUri != target) return

        val uri = target.toUri()
        val label = backing.pendingLibraryFolderLabel
            ?: backing.libraryFolderLabel
            ?: LibraryFolderStore.displayName(backing.context, uri)
        LibraryScanSettings.setLibraryFolder(backing.context, uri, label)
        LibraryScanSettings.clearPendingLibraryFolder(backing.context)
        backing.libraryFolderUri = target
        backing.libraryFolderLabel = label
        backing.pendingLibraryFolderUri = null
        backing.pendingLibraryFolderLabel = null
    }

    fun discardPendingFolderIfMatches(token: LibraryOperationToken) {
        val target = token.sourceIdentity.folderTreeUriOrNull() ?: return
        if (backing.pendingLibraryFolderUri != target) return
        LibraryScanSettings.clearPendingLibraryFolder(backing.context)
        backing.pendingLibraryFolderUri = null
        backing.pendingLibraryFolderLabel = null
    }

    fun discardPendingFolderSelection() {
        if (backing.pendingLibraryFolderUri == null) return
        LibraryScanSettings.clearPendingLibraryFolder(backing.context)
        backing.pendingLibraryFolderUri = null
        backing.pendingLibraryFolderLabel = null
    }

    fun reconcileWithPersistedSourceState() {
        val activeUri = backing.sourceState.active
            ?.sourceIdentity
            ?.folderTreeUriOrNull()
            ?: return
        val activeTree = activeUri.toUri()
        val pendingMatches = backing.pendingLibraryFolderUri == activeUri
        val label = when {
            pendingMatches -> backing.pendingLibraryFolderLabel
            backing.libraryFolderUri == activeUri -> backing.libraryFolderLabel
            else -> null
        } ?: LibraryFolderStore.displayName(backing.context, activeTree)

        backing.libraryFolderUri = activeUri
        backing.libraryFolderLabel = label
        LibraryScanSettings.setLibraryFolder(backing.context, activeTree, label)
        if (pendingMatches) {
            LibraryScanSettings.clearPendingLibraryFolder(backing.context)
            backing.pendingLibraryFolderUri = null
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
        DiagnosticLog.event(
            "LibraryResume",
            "clearLibrary start songs=${backing.songs.size} hasScanned=${backing.hasScanned} " +
                "lastScanAtMs=${backing.lastScanAtMs}",
        )
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
        val startedMs = SystemClock.elapsedRealtime()
        val clearedState = backing.clearedPersistedState()
        var publishedGeneration: Int? = null
        backing.replaceSnapshotAuthority(
            storeBlock = {
                backing.libraryStore.clearAuthority(clearedState)
            },
            publishBlock = { _, generation ->
                publishedGeneration = generation
                backing.restorePersistedState(clearedState)
                backing.catalog.clearCatalog()
                backing.hasScanned = false
                backing.totalSizeMb = 0
                backing.lastScanAtMs = null
                backing.lastScanError = null
                backing.lastScanSyncSummary = null
            },
        ) ?: return
        DiagnosticLog.event(
            "LibraryResume",
            "clearLibrary storeClear end durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                "generation=${publishedGeneration ?: backing.scanGeneration}",
        )
    }
}
