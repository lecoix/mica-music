package com.mica.music.data

import android.net.Uri

data class PermissionRefreshResult(
    val granted: Boolean,
    val shouldOpenSettings: Boolean,
    val previousGranted: Boolean,
)

sealed class LibraryScanRequest {
    data object Rescan : LibraryScanRequest()
    data object ScanDeviceWide : LibraryScanRequest()
    data object ScanLibraryFolder : LibraryScanRequest()
}

sealed class LibraryAccessRequest {
    data object OpenAppSettings : LibraryAccessRequest()
    data object RequestAudioPermission : LibraryAccessRequest()
    data class ExecuteScan(val scan: LibraryScanRequest) : LibraryAccessRequest()
}

/**
 * 曲库权限、SAF 文件夹选择与扫描触发决策。
 * Activity Result launcher 仍留在 Composable；此处只做状态刷新与动作解析。
 */
object LibraryAccessCoordinator {
    fun refreshPermission(
        library: MusicLibrary,
        permissionRequestAttempted: Boolean,
        shouldShowPermissionRationale: Boolean,
    ): PermissionRefreshResult {
        val previousGranted = library.permissionGranted
        val granted = library.hasAudioReadPermission()
        library.updatePermission(granted)
        val shouldOpenSettings = !granted &&
            permissionRequestAttempted &&
            !shouldShowPermissionRationale
        return PermissionRefreshResult(
            granted = granted,
            shouldOpenSettings = shouldOpenSettings,
            previousGranted = previousGranted,
        )
    }

    fun onPermissionResult(
        library: MusicLibrary,
        granted: Boolean,
        permissionRequestAttempted: Boolean,
        shouldShowPermissionRationale: Boolean,
    ): Pair<PermissionRefreshResult, LibraryScanRequest?> {
        val previousGranted = library.permissionGranted
        library.updatePermission(granted)
        val refresh = PermissionRefreshResult(
            granted = granted,
            shouldOpenSettings = !granted &&
                permissionRequestAttempted &&
                !shouldShowPermissionRationale,
            previousGranted = previousGranted,
        )
        val scan = if (granted) LibraryScanRequest.ScanDeviceWide else null
        return refresh to scan
    }

    fun onFolderPicked(library: MusicLibrary, uri: Uri): LibraryScanRequest {
        library.setLibraryFolder(uri)
        return LibraryScanRequest.ScanLibraryFolder
    }

    fun resolveFullScanRequest(
        library: MusicLibrary,
        shouldOpenSettings: Boolean,
    ): LibraryAccessRequest = when {
        shouldOpenSettings -> LibraryAccessRequest.OpenAppSettings
        library.permissionGranted -> LibraryAccessRequest.ExecuteScan(LibraryScanRequest.ScanDeviceWide)
        else -> LibraryAccessRequest.RequestAudioPermission
    }

    fun resolveStartScanRequest(library: MusicLibrary): LibraryAccessRequest = when {
        library.hasLibraryFolder() ->
            LibraryAccessRequest.ExecuteScan(LibraryScanRequest.ScanLibraryFolder)
        library.hasAudioReadPermission() ->
            LibraryAccessRequest.ExecuteScan(LibraryScanRequest.ScanDeviceWide)
        else -> LibraryAccessRequest.RequestAudioPermission
    }

    /** Home 顶栏/统计栏重扫：不额外拦截权限（与 Settings 不同）。 */
    fun resolveHomeRescanRequest(): LibraryAccessRequest =
        LibraryAccessRequest.ExecuteScan(LibraryScanRequest.Rescan)

    /** Settings 重扫：无文件夹且无读权限时先引导授权。 */
    fun resolveSettingsRescanRequest(
        library: MusicLibrary,
        shouldShowPermissionRationale: Boolean,
    ): LibraryAccessRequest? = when {
        library.hasLibraryFolder() || library.hasAudioReadPermission() -> null
        shouldShowPermissionRationale -> LibraryAccessRequest.RequestAudioPermission
        else -> LibraryAccessRequest.OpenAppSettings
    }

    fun executeScan(library: MusicLibrary, scan: LibraryScanRequest) {
        when (scan) {
            LibraryScanRequest.Rescan -> library.launchRescan()
            LibraryScanRequest.ScanDeviceWide -> library.launchScanDeviceWide()
            LibraryScanRequest.ScanLibraryFolder -> library.launchScanLibraryFolder()
        }
    }
}
