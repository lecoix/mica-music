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
        val uri = LibraryScanSettings.libraryTreeUri(backing.context)
        backing.libraryFolderUri = uri?.toString()
        backing.libraryFolderLabel = LibraryScanSettings.libraryFolderLabel(backing.context)
    }

    fun hasLibraryFolder(): Boolean = !backing.libraryFolderUri.isNullOrBlank()

    fun setLibraryFolder(treeUri: Uri) {
        LibraryFolderStore.persistTreeAccess(backing.context, treeUri)
        val label = LibraryFolderStore.displayName(backing.context, treeUri)
        LibraryScanSettings.setLibraryFolder(backing.context, treeUri, label)
        backing.libraryFolderUri = treeUri.toString()
        backing.libraryFolderLabel = label
    }

    fun clearLibraryFolder() {
        backing.libraryFolderUri?.toUri()?.let { uri ->
            LibraryFolderStore.releaseTreeAccess(backing.context, uri)
        }
        LibraryScanSettings.clearLibraryFolder(backing.context)
        backing.libraryFolderUri = null
        backing.libraryFolderLabel = null
    }

    fun updatePermission(granted: Boolean) {
        DiagnosticLog.event(
            "LibraryResume",
            "updatePermission granted=$granted previous=${backing.permissionGranted} " +
                "hasFolder=${hasLibraryFolder()} hasScanned=${backing.hasScanned} songs=${backing.songs.size}",
        )
        backing.permissionGranted = granted
        if (!granted && !hasLibraryFolder()) {
            clearLibrary()
        }
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
        backing.catalog.clearCatalog()
        backing.hasScanned = false
        backing.totalSizeMb = 0
        backing.lastScanAtMs = null
        backing.lastScanError = null
        backing.scanProgressLabel = null
        backing.isScanning = false
        backing.isLoadingCachedLibrary = false
        backing.ioScope.launch {
            val startedMs = SystemClock.elapsedRealtime()
            backing.libraryStore.clear()
            DiagnosticLog.event(
                "LibraryResume",
                "clearLibrary storeClear end durMs=${SystemClock.elapsedRealtime() - startedMs}",
            )
        }
    }
}
