package com.mica.music.ui.screens.home

import android.net.Uri
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.mica.music.data.LibraryAccessCoordinator
import com.mica.music.data.LibraryAccessRequest
import com.mica.music.data.MusicLibrary
import com.mica.music.util.DiagnosticLog
import com.mica.music.util.openAppSettings

class HomeLibraryAccessHandle internal constructor(
    internal val shouldOpenSettingsReader: () -> Boolean,
    val onPickLibraryFolder: () -> Unit,
    val onRequestFullScan: () -> Unit,
    val onStartScan: () -> Unit,
    val onRequestRescan: () -> Unit,
    internal val refreshPermission: (reason: String) -> Boolean,
) {
    val shouldOpenSettings: Boolean
        get() = shouldOpenSettingsReader()
}

@Composable
fun rememberHomeLibraryAccess(
    library: MusicLibrary,
    activity: ComponentActivity,
    onResumeWithPermission: (granted: Boolean) -> Unit = {},
): HomeLibraryAccessHandle {
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioPermission = remember(library) { library.audioReadPermission() }
    var permissionRequestAttempted by remember { mutableStateOf(false) }
    var shouldOpenSettings by remember { mutableStateOf(false) }
    val latestOnResume by rememberUpdatedState(onResumeWithPermission)

    fun shouldShowPermissionRationale(): Boolean =
        activity.shouldShowRequestPermissionRationale(audioPermission)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionRequestAttempted = true
        val (refresh, scan) = LibraryAccessCoordinator.onPermissionResult(
            library = library,
            granted = granted,
            permissionRequestAttempted = permissionRequestAttempted,
            shouldShowPermissionRationale = shouldShowPermissionRationale(),
        )
        shouldOpenSettings = refresh.shouldOpenSettings
        scan?.let { LibraryAccessCoordinator.executeScan(library, it) }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val scan = LibraryAccessCoordinator.onFolderPicked(library, uri)
        LibraryAccessCoordinator.executeScan(library, scan)
    }

    fun dispatchAccessRequest(request: LibraryAccessRequest) {
        when (request) {
            LibraryAccessRequest.OpenAppSettings -> openAppSettings(activity)
            LibraryAccessRequest.RequestAudioPermission ->
                permissionLauncher.launch(audioPermission)
            is LibraryAccessRequest.ExecuteScan ->
                LibraryAccessCoordinator.executeScan(library, request.scan)
        }
    }

    fun refreshPermissionState(reason: String): Boolean {
        val startedMs = SystemClock.elapsedRealtime()
        val result = LibraryAccessCoordinator.refreshPermission(
            library = library,
            permissionRequestAttempted = permissionRequestAttempted,
            shouldShowPermissionRationale = shouldShowPermissionRationale(),
        )
        shouldOpenSettings = result.shouldOpenSettings
        DiagnosticLog.event(
            "LibraryResume",
            "permissionRefresh reason=$reason durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                "granted=${result.granted} previous=${result.previousGranted} " +
                "attempted=$permissionRequestAttempted shouldOpenSettings=$shouldOpenSettings " +
                "hasScanned=${library.hasScanned} songs=${library.songs.size} " +
                "hasFolder=${library.hasLibraryFolder()}",
        )
        return result.granted
    }

    LaunchedEffect(Unit) {
        refreshPermissionState("initial")
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = refreshPermissionState("onResume")
                latestOnResume(granted)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return HomeLibraryAccessHandle(
        shouldOpenSettingsReader = { shouldOpenSettings },
        onPickLibraryFolder = { folderPickerLauncher.launch(null) },
        onRequestFullScan = {
            dispatchAccessRequest(
                LibraryAccessCoordinator.resolveFullScanRequest(
                    library = library,
                    shouldOpenSettings = shouldOpenSettings,
                ),
            )
        },
        onStartScan = {
            dispatchAccessRequest(
                LibraryAccessCoordinator.resolveStartScanRequest(library),
            )
        },
        onRequestRescan = {
            dispatchAccessRequest(LibraryAccessCoordinator.resolveHomeRescanRequest())
        },
        refreshPermission = ::refreshPermissionState,
    )
}
