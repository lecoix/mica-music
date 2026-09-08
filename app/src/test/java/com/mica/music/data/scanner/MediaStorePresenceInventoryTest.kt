package com.mica.music.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaStorePresenceInventoryTest {

    @Test
    fun eligibilityKeepsFilteredRowsAsPositivePresenceFacts() {
        assertEquals(
            LibraryEligibility.FILTERED_OUT,
            MediaStorePresenceInventory.mediaStoreEligibility(
                pending = false,
                trashed = false,
                eligibleByType = false,
                eligibleByDuration = true,
                excluded = false,
            ),
        )
        assertEquals(
            LibraryEligibility.FILTERED_OUT,
            MediaStorePresenceInventory.mediaStoreEligibility(
                pending = false,
                trashed = false,
                eligibleByType = true,
                eligibleByDuration = false,
                excluded = false,
            ),
        )
        assertEquals(
            LibraryEligibility.FILTERED_OUT,
            MediaStorePresenceInventory.mediaStoreEligibility(
                pending = false,
                trashed = false,
                eligibleByType = true,
                eligibleByDuration = true,
                excluded = true,
            ),
        )
    }

    @Test
    fun pendingAndTrashedAreExplicitPresenceStatesNotAbsence() {
        assertEquals(
            LibraryEligibility.PENDING,
            MediaStorePresenceInventory.mediaStoreEligibility(
                pending = true,
                trashed = false,
                eligibleByType = true,
                eligibleByDuration = true,
                excluded = false,
            ),
        )
        assertEquals(
            LibraryEligibility.TRASHED,
            MediaStorePresenceInventory.mediaStoreEligibility(
                pending = false,
                trashed = true,
                eligibleByType = true,
                eligibleByDuration = true,
                excluded = false,
            ),
        )
    }

    @Test
    fun pendingWinsOverTrashedAsTheConservativeState() {
        assertEquals(
            LibraryEligibility.PENDING,
            MediaStorePresenceInventory.mediaStoreEligibility(
                pending = true,
                trashed = true,
                eligibleByType = false,
                eligibleByDuration = false,
                excluded = true,
            ),
        )
    }

    @Test
    fun mediaStoreIdUsesSameStableObjectIdentityAsScannerSongs() {
        assertEquals("ms_42", MediaStorePresenceInventory.mediaStoreStableObjectKey(42L))
    }
}
