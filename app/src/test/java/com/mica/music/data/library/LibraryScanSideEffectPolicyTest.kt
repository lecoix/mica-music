package com.mica.music.data.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScanSideEffectPolicyTest {

    @Test
    fun fullScanOwnsGlobalMaintenanceAndUserVisibleScanMetadata() {
        val policy = LibraryScanSideEffectPolicy.forMode(LibraryOperationMode.FULL)

        assertTrue(policy.ownsGlobalLyricsMaintenance)
        assertTrue(policy.publishUserScanMetadata)
        assertTrue(policy.runAlbumArtMaintenance)
        assertTrue(policy.prefetchVideoCoverPosters)
        assertTrue(policy.clearTransientScanCache)
    }

    @Test
    fun targetedRefreshCannotCompleteGlobalMaintenanceOrMasqueradeAsFullScan() {
        val policy = LibraryScanSideEffectPolicy.forMode(LibraryOperationMode.TARGETED_REFRESH)

        assertFalse(policy.ownsGlobalLyricsMaintenance)
        assertFalse(policy.publishUserScanMetadata)
        assertFalse(policy.runAlbumArtMaintenance)
        assertFalse(policy.prefetchVideoCoverPosters)
        assertTrue(policy.clearTransientScanCache)
    }

    @Test
    fun autoSyncIsSilentAndMaintenanceFreeByDefault() {
        val policy = LibraryScanSideEffectPolicy.forMode(LibraryOperationMode.AUTO_SYNC)

        assertFalse(policy.ownsGlobalLyricsMaintenance)
        assertFalse(policy.publishUserScanMetadata)
        assertFalse(policy.runAlbumArtMaintenance)
        assertFalse(policy.prefetchVideoCoverPosters)
        assertFalse(policy.clearTransientScanCache)
    }
}
