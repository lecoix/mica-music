package com.mica.music.ui.screens.settings

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.mica.music.data.LibraryAccessCoordinator
import com.mica.music.data.LibraryAccessRequest
import com.mica.music.data.LibraryScanRequest
import com.mica.music.data.MusicLibrary
import com.mica.music.util.openAppSettings

class SettingsLibraryAccessHandle(
    val onChooseLibraryFolder: () -> Unit,
    val onRescan: () -> Unit,
    val onScanAllMusic: () -> Unit,
)

@Composable
fun rememberSettingsLibraryAccess(
    library: MusicLibrary,
    activity: ComponentActivity,
): SettingsLibraryAccessHandle {
    val audioPermission = remember(library) { library.audioReadPermission() }

    fun shouldShowPermissionRationale(): Boolean =
        activity.shouldShowRequestPermissionRationale(audioPermission)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val (_, scan) = LibraryAccessCoordinator.onPermissionResult(
            library = library,
            granted = granted,
            permissionRequestAttempted = true,
            shouldShowPermissionRationale = shouldShowPermissionRationale(),
        )
        scan?.let { LibraryAccessCoordinator.executeScan(library, it) }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val scan = LibraryAccessCoordinator.onFolderPicked(library, uri)
        LibraryAccessCoordinator.executeScan(library, scan)
    }

    fun dispatchRescan() {
        when (
            val gate = LibraryAccessCoordinator.resolveSettingsRescanRequest(
                library = library,
                shouldShowPermissionRationale = shouldShowPermissionRationale(),
            )
        ) {
            null -> LibraryAccessCoordinator.executeScan(library, LibraryScanRequest.Rescan)
            LibraryAccessRequest.OpenAppSettings -> openAppSettings(activity)
            LibraryAccessRequest.RequestAudioPermission ->
                permissionLauncher.launch(audioPermission)
            is LibraryAccessRequest.ExecuteScan ->
                LibraryAccessCoordinator.executeScan(library, gate.scan)
        }
    }

    fun dispatchScanAllMusic() {
        if (library.hasAudioReadPermission()) {
            LibraryAccessCoordinator.executeScan(library, LibraryScanRequest.ScanDeviceWide)
        } else {
            permissionLauncher.launch(audioPermission)
        }
    }

    return SettingsLibraryAccessHandle(
        onChooseLibraryFolder = { folderPickerLauncher.launch(null) },
        onRescan = ::dispatchRescan,
        onScanAllMusic = ::dispatchScanAllMusic,
    )
}
