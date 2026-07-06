package com.mica.music.data

import com.mica.music.data.preferences.LibraryScanSettings
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryAccessCoordinatorTest {
    private lateinit var library: MusicLibrary
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        library = MusicLibrary(context)
        library.updatePermission(false)
        library.clearLibraryFolder()
    }

    @Test
    fun refreshPermissionSyncsPermissionGrantedFlag() {
        val result = LibraryAccessCoordinator.refreshPermission(
            library = library,
            permissionRequestAttempted = true,
            shouldShowPermissionRationale = false,
        )

        assertEquals(library.permissionGranted, result.granted)
        assertEquals(library.permissionGranted, result.previousGranted || result.granted)
    }

    @Test
    fun onPermissionResultTriggersDeviceScanWhenGranted() {
        val (refresh, scan) = LibraryAccessCoordinator.onPermissionResult(
            library = library,
            granted = true,
            permissionRequestAttempted = true,
            shouldShowPermissionRationale = false,
        )

        assertEquals(true, refresh.granted)
        assertEquals(LibraryScanRequest.ScanDeviceWide, scan)
    }

    @Test
    fun resolveFullScanOpensSettingsWhenGateActive() {
        val request = LibraryAccessCoordinator.resolveFullScanRequest(
            library = library,
            shouldOpenSettings = true,
        )
        assertEquals(LibraryAccessRequest.OpenAppSettings, request)
    }

    @Test
    fun resolveFullScanRequestsPermissionWhenNotGranted() {
        val request = LibraryAccessCoordinator.resolveFullScanRequest(
            library = library,
            shouldOpenSettings = false,
        )
        assertEquals(LibraryAccessRequest.RequestAudioPermission, request)
    }

    @Test
    fun resolveFullScanScansDeviceWhenGranted() {
        library.updatePermission(true)
        val request = LibraryAccessCoordinator.resolveFullScanRequest(
            library = library,
            shouldOpenSettings = false,
        )
        assertEquals(
            LibraryAccessRequest.ExecuteScan(LibraryScanRequest.ScanDeviceWide),
            request,
        )
    }

    @Test
    fun resolveStartScanPrefersLibraryFolder() {
        LibraryScanSettings.setLibraryFolder(
            context,
            Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic"),
            "Music",
        )
        library.reloadLibraryFolderFromPrefs()

        val request = LibraryAccessCoordinator.resolveStartScanRequest(library)
        assertEquals(
            LibraryAccessRequest.ExecuteScan(LibraryScanRequest.ScanLibraryFolder),
            request,
        )
    }

    @Test
    fun resolveStartScanRequestsPermissionWhenMissingAccess() {
        val request = LibraryAccessCoordinator.resolveStartScanRequest(library)
        if (library.hasAudioReadPermission()) {
            assertEquals(
                LibraryAccessRequest.ExecuteScan(LibraryScanRequest.ScanDeviceWide),
                request,
            )
        } else {
            assertEquals(LibraryAccessRequest.RequestAudioPermission, request)
        }
    }

    @Test
    fun resolveSettingsRescanRequiresPermissionOrSettingsWhenBlocked() {
        if (library.hasAudioReadPermission() || library.hasLibraryFolder()) return

        assertEquals(
            LibraryAccessRequest.RequestAudioPermission,
            LibraryAccessCoordinator.resolveSettingsRescanRequest(
                library = library,
                shouldShowPermissionRationale = true,
            ),
        )
        assertEquals(
            LibraryAccessRequest.OpenAppSettings,
            LibraryAccessCoordinator.resolveSettingsRescanRequest(
                library = library,
                shouldShowPermissionRationale = false,
            ),
        )
    }

    @Test
    fun resolveSettingsRescanProceedsWhenFolderConfigured() {
        LibraryScanSettings.setLibraryFolder(
            context,
            Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic"),
            "Music",
        )
        library.reloadLibraryFolderFromPrefs()

        assertNull(
            LibraryAccessCoordinator.resolveSettingsRescanRequest(
                library = library,
                shouldShowPermissionRationale = false,
            ),
        )
    }

    @Test
    fun homeRescanAlwaysExecutesRescan() {
        assertEquals(
            LibraryAccessRequest.ExecuteScan(LibraryScanRequest.Rescan),
            LibraryAccessCoordinator.resolveHomeRescanRequest(),
        )
    }
}
