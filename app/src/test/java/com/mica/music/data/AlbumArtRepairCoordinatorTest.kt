package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumArtRepairCoordinatorTest {

    @Test
    fun folderSourcePrefersFolderRepairWhenFolderIsAvailable() {
        assertEquals(
            AlbumArtRepairAction.ScanFolder,
            AlbumArtRepairCoordinator.actionFor(
                lastScanSource = ScanSource.FOLDER,
                hasLibraryFolder = true,
                hasAudioReadPermission = true,
            ),
        )
    }

    @Test
    fun folderSourceFallsBackToDeviceRepairWhenFolderIsMissing() {
        assertEquals(
            AlbumArtRepairAction.ScanDevice,
            AlbumArtRepairCoordinator.actionFor(
                lastScanSource = ScanSource.FOLDER,
                hasLibraryFolder = false,
                hasAudioReadPermission = true,
            ),
        )
    }

    @Test
    fun deviceSourceFallsBackToFolderRepairWhenDevicePermissionIsMissing() {
        assertEquals(
            AlbumArtRepairAction.ScanFolder,
            AlbumArtRepairCoordinator.actionFor(
                lastScanSource = ScanSource.DEVICE,
                hasLibraryFolder = true,
                hasAudioReadPermission = false,
            ),
        )
    }

    @Test
    fun noReadableSourceSkipsRepair() {
        assertEquals(
            AlbumArtRepairAction.NoReadableSource,
            AlbumArtRepairCoordinator.actionFor(
                lastScanSource = ScanSource.DEVICE,
                hasLibraryFolder = false,
                hasAudioReadPermission = false,
            ),
        )
    }
}
